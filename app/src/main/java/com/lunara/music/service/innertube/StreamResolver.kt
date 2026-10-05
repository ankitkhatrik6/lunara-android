package com.lunara.music.service.innertube

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Turns a YouTube Music video id into a stream that is *proven* playable.
 *
 * Resolution is done entirely in process by [InnerTubePlayer] talking to the
 * InnerTube `/player` endpoint, and every result is deep-validated by
 * [StreamValidator] before it is cached or handed to the player. Public
 * Piped / Invidious mirrors are kept only as a last-resort safety net.
 *
 * The cache stores whole [StreamCandidate]s — headers included — because the
 * media request must be signed with the same identity that minted the URL.
 * Caching only the URL string is what used to send an iOS-minted URL to the CDN
 * with a browser User-Agent, which YouTube answers with a mid-track 403.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val MAX_CACHE_ENTRIES = 48

    private val mirrorClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = object : LinkedHashMap<String, StreamCandidate>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamCandidate>?) =
            size > MAX_CACHE_ENTRIES
    }

    /** Serialises resolution per video so a burst of taps resolves once. */
    private val resolveMutex = Mutex()

    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Warm the visitor id so the first song resolves without an extra round trip. */
    fun init() {
        warmupScope.launch {
            runCatching { InnerTubeSession.refreshVisitorData() }
            runCatching { InnerTubePlayer.ensureVisitorData() }
        }
    }

    /**
     * Resolve [videoId] to a playable stream, or null when every source failed.
     *
     * Cached, non-expired candidates are returned immediately; everything else
     * is resolved and deep-validated under a per-video lock so concurrent
     * requests for the same track share one resolution.
     */
    suspend fun resolve(videoId: String): StreamCandidate? {
        if (videoId.isBlank()) return null
        cached(videoId)?.let { return it }

        return resolveMutex.withLock {
            cached(videoId)?.let { return@withLock it }

            // A stale anonymous identity makes the catalogue answer
            // LOGIN_REQUIRED for every play, so refresh before each resolve.
            runCatching { InnerTubeSession.refreshVisitorData() }

            val candidate = StreamValidator.resolvePlayable(videoId)
            if (candidate != null) {
                store(videoId, candidate)
            } else {
                Log.e(TAG, "No playable stream could be resolved for $videoId")
            }
            candidate
        }
    }

    /**
     * Resolve ignoring the cache, but still excluding any candidate that has
     * already expired. Used after a playback failure so a dead URL is never
     * replayed.
     */
    suspend fun resolveFresh(videoId: String): StreamCandidate? {
        invalidate(videoId)
        return resolve(videoId)
    }

    /** Drops the cached stream for [videoId] (e.g. after a playback failure). */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    /** Headers the player must send for [candidate]; empty for local files. */
    fun headersFor(candidate: StreamCandidate?): Map<String, String> =
        candidate?.headers.orEmpty()

    private fun cached(videoId: String): StreamCandidate? {
        synchronized(cache) {
            val entry = cache[videoId] ?: return null
            // Never replay a URL that has passed its `expire` deadline.
            if (entry.isExpired) {
                cache.remove(videoId)
                return null
            }
            return entry
        }
    }

    private fun store(videoId: String, candidate: StreamCandidate) {
        synchronized(cache) { cache[videoId] = candidate }
    }

    /**
     * Last-resort resolution through public Piped / Invidious mirrors.
     *
     * These instances mint their own googlevideo URLs, so their output is not
     * subject to our IP's GVS throttle. Redirects are followed because several
     * instances hand out 302s to a CDN host, and every URL is deep-validated
     * before it is accepted.
     */
    suspend fun resolveViaMirrors(videoId: String): StreamCandidate? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        for (instance in MIRRORS) {
            val candidate = runCatching { queryMirror(instance, videoId) }.getOrNull()
            if (candidate == null) continue
            if (StreamValidator.isPlayable(candidate)) {
                Log.i(TAG, "Resolved $videoId via mirror ${instance.host}")
                return@withContext candidate
            }
            Log.d(TAG, "Mirror ${instance.host} returned a capped stream; trying next")
        }
        null
    }

    private data class Mirror(val host: String, val apiUrl: String, val flavour: Flavour)

    private enum class Flavour { PIPED, INVIDIOUS }

    /**
     * Mirrors are volatile by nature (public instances go up and down all the
     * time), so each one is deep-validated rather than trusted. Kept ordered by
     * observed reliability.
     */
    private val MIRRORS = listOf(
        Mirror("invidious.f5.si", "https://invidious.f5.si/api/v1/videos", Flavour.INVIDIOUS),
        Mirror("piped.adminforge.de", "https://pipedapi.adminforge.de/streams", Flavour.PIPED),
        Mirror("piped.reallyaweso.me", "https://pipedapi.reallyaweso.me/streams", Flavour.PIPED),
        Mirror("piped.leptons.xyz", "https://pipedapi.leptons.xyz/streams", Flavour.PIPED),
        Mirror("inv.nadeko.net", "https://inv.nadeko.net/api/v1/videos", Flavour.INVIDIOUS),
        Mirror("invidious.nerdvpn.de", "https://invidious.nerdvpn.de/api/v1/videos", Flavour.INVIDIOUS),
        Mirror("yewtu.be", "https://yewtu.be/api/v1/videos", Flavour.INVIDIOUS),
    )

    private fun queryMirror(mirror: Mirror, videoId: String): StreamCandidate? {
        val request = Request.Builder()
            .url("${mirror.apiUrl}/$videoId")
            .header("User-Agent", InnerTubeClients.USER_AGENT_WEB)
            .build()

        val body = mirrorClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val headers = mapOf("User-Agent" to InnerTubeClients.USER_AGENT_WEB)

        return when (mirror.flavour) {
            Flavour.PIPED -> parsePiped(json, videoId, headers)
            Flavour.INVIDIOUS -> parseInvidious(json, videoId, headers)
        }
    }

    /** Piped: `{ "audioStreams": [ { "url", "bitrate" } ] }`. */
    private fun parsePiped(json: JSONObject, videoId: String, headers: Map<String, String>): StreamCandidate? {
        val streams = json.optJSONArray("audioStreams") ?: return null
        var best: StreamCandidate? = null
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val url = stream.optString("url", "")
            if (url.isBlank()) continue
            val candidate = StreamCandidate(
                url = url,
                mimeType = stream.optString("mimeType", ""),
                bitrate = stream.optInt("bitrate", 0),
                contentLength = stream.optString("size", "").toLongOrNull() ?: 0L,
                clientName = "mirror",
                headers = headers,
                expiresAtMs = System.currentTimeMillis() + MIRROR_URL_LIFETIME_MS,
                source = "mirror",
            )
            if (best == null || candidate.bitrate > best.bitrate) best = candidate
        }
        Log.d(TAG, "Piped offered ${streams.length()} audio streams for $videoId")
        return best
    }

    /** Invidious: `{ "adaptiveFormats": [ { "type", "url", "bitrate" } ] }`. */
    private fun parseInvidious(json: JSONObject, videoId: String, headers: Map<String, String>): StreamCandidate? {
        val formats = json.optJSONArray("adaptiveFormats") ?: return null
        var best: StreamCandidate? = null
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            if (!format.optString("type", "").contains("audio", ignoreCase = true)) continue
            val url = format.optString("url", "")
            if (url.isBlank()) continue
            val candidate = StreamCandidate(
                url = url,
                mimeType = format.optString("type", "")
                    .substringAfter(";").trim().ifBlank { "audio/mp4" },
                bitrate = format.optString("bitrate", "").toLongOrNull()?.toInt() ?: 0,
                contentLength = 0L,
                clientName = "mirror",
                headers = headers,
                expiresAtMs = System.currentTimeMillis() + MIRROR_URL_LIFETIME_MS,
                source = "mirror",
            )
            if (best == null || candidate.bitrate > best.bitrate) best = candidate
        }
        Log.d(TAG, "Invidious offered ${formats.length()} formats for $videoId")
        return best
    }

    private const val MIRROR_URL_LIFETIME_MS = 30 * 60 * 1000L
}
