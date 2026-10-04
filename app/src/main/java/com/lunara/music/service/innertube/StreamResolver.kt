package com.lunara.music.service.innertube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube Music video id into a directly playable audio URL.
 *
 * The primary engine is BlazifyExtractor, the same stream-extraction library
 * that powers Blazify. It resolves the best audio stream for the current network
 * and device and performs the YouTube signature (cipher) deobfuscation and
 * PoToken handling that a raw InnerTube `/player` response requires.
 *
 * When the extractor cannot produce a URL (region locks, transient upstream
 * changes) we fall back to public Piped / Invidious mirrors.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 48

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var isInitialized = false

    private data class CachedUrl(val url: String, val resolvedAt: Long)

    private val cache = object : LinkedHashMap<String, CachedUrl>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedUrl>?) =
            size > MAX_CACHE_ENTRIES
    }

    fun init() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                NewPipe.init(
                    LunaraDownloader(client),
                    Localization.DEFAULT,
                    ContentCountry.DEFAULT
                )
                isInitialized = true
                Log.d(TAG, "Stream extractor initialized")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize stream extractor", e)
            }
        }
    }

    /**
     * Returns a playable audio URL for [videoId], or null when nothing could be
     * resolved. Resolved URLs are cached briefly because they are time limited.
     */
    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        cached(videoId)?.let { return@withContext it }

        val fromExtractor = runCatching { extractWithBlazify(videoId) }
            .onFailure { Log.w(TAG, "Extractor failed for $videoId: ${it.message}") }
            .getOrNull()
        if (!fromExtractor.isNullOrBlank()) {
            store(videoId, fromExtractor)
            return@withContext fromExtractor
        }

        val fromMirror = resolveViaMirrors(videoId)
        if (!fromMirror.isNullOrBlank()) {
            store(videoId, fromMirror)
            return@withContext fromMirror
        }

        Log.e(TAG, "Could not resolve a playable stream for $videoId")
        null
    }

    /** Clears the resolved-URL cache (e.g. after a playback failure). */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    private fun extractWithBlazify(videoId: String): String? {
        init()
        val service = ServiceList.YouTube
        val streamInfo = try {
            StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: ExtractionException) {
            Log.w(TAG, "Extraction exception for $videoId: ${e.message}")
            return null
        }

        val candidates = streamInfo.audioStreams
            .filter { !it.content.isNullOrBlank() }
        val best: AudioStream? = candidates
            .filter { it.averageBitrate > 0 }
            .maxByOrNull { it.averageBitrate }
            ?: candidates.firstOrNull()

        if (best != null && !best.content.isNullOrBlank()) {
            Log.d(
                TAG,
                "Resolved $videoId via BlazifyExtractor " +
                    "(${best.averageBitrate} kbps, ${best.format})"
            )
            return best.content
        }
        return null
    }

    private fun resolveViaMirrors(videoId: String): String? {
        // Best-effort public mirrors. These are only a safety net; the extractor
        // above is the primary path.
        val instances = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://pipedapi.adminforge.de/streams/$videoId",
            "https://api.piped.private.coffee/streams/$videoId",
            "https://inv.nadeko.net/api/v1/videos/$videoId",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId"
        )

        for (instance in instances) {
            val url = runCatching { queryMirror(instance) }.getOrNull()
            if (!url.isNullOrBlank()) {
                Log.d(TAG, "Resolved $videoId via mirror $instance")
                return url
            }
        }
        return null
    }

    private fun queryMirror(instanceUrl: String): String? {
        val req = Request.Builder()
            .url(instanceUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)

            // Piped: { "audioStreams": [ { "url", "bitrate", "mimeType" } ] }
            json.optJSONArray("audioStreams")?.let { streams ->
                var bestUrl: String? = null
                var bestBitrate = -1
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val url = s.optString("url", "")
                    val bitrate = s.optInt("bitrate", 0)
                    if (url.isNotBlank() && bitrate >= bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = url
                    }
                }
                if (!bestUrl.isNullOrBlank()) return bestUrl
            }

            // Invidious: { "adaptiveFormats": [ { "type", "url" } ] }
            json.optJSONArray("adaptiveFormats")?.let { formats ->
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val type = f.optString("type", "")
                    val url = f.optString("url", "")
                    if (type.contains("audio", ignoreCase = true) && url.isNotBlank()) {
                        return url
                    }
                }
            }
        }
        return null
    }

    private fun cached(videoId: String): String? {
        synchronized(cache) {
            val entry = cache[videoId] ?: return null
            if (System.currentTimeMillis() - entry.resolvedAt > CACHE_TTL_MS) {
                cache.remove(videoId)
                return null
            }
            return entry.url
        }
    }

    private fun store(videoId: String, url: String) {
        synchronized(cache) { cache[videoId] = CachedUrl(url, System.currentTimeMillis()) }
    }
}
