package com.lunara.music.service.innertube

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube Music video id into a directly playable audio URL.
 *
 * Resolution is done entirely in process by [InnerTubePlayer], which talks to
 * the InnerTube `/player` endpoint and reads a plain (non-ciphered) audio URL.
 * No external extractor library is used. Public Piped / Invidious mirrors are
 * kept only as a last resort safety net.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 48

    private val mirrorClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class CachedUrl(val url: String, val resolvedAt: Long)

    private val cache = object : LinkedHashMap<String, CachedUrl>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedUrl>?) =
            size > MAX_CACHE_ENTRIES
    }

    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Warm the visitor id so the first song resolves without an extra round trip. */
    fun init() {
        warmupScope.launch { runCatching { InnerTubePlayer.ensureVisitorData() } }
    }

    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        cached(videoId)?.let { return@withContext it }

        // 1. InnerTube player. Clients that return direct audio URLs are tried first.
        for (client in InnerTubeClients.STREAM_CLIENTS) {
            val response = runCatching { InnerTubePlayer.fetchPlayerResponse(client, videoId) }
                .getOrNull() ?: continue
            val url = InnerTubePlayer.selectAudioUrl(response)
            if (!url.isNullOrBlank() && InnerTubePlayer.validateUrl(url)) {
                Log.d(TAG, "Resolved $videoId via ${client.clientName}")
                store(videoId, url)
                return@withContext url
            }
        }

        // 2. Public mirrors as a safety net.
        val mirror = resolveViaMirrors(videoId)
        if (!mirror.isNullOrBlank()) {
            Log.d(TAG, "Resolved $videoId via mirror")
            store(videoId, mirror)
            return@withContext mirror
        }

        Log.e(TAG, "Could not resolve a playable stream for $videoId")
        null
    }

    /** Clears the resolved-URL cache (e.g. after a playback failure). */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    private fun resolveViaMirrors(videoId: String): String? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://pipedapi.adminforge.de/streams/$videoId",
            "https://api.piped.private.coffee/streams/$videoId",
            "https://inv.nadeko.net/api/v1/videos/$videoId",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId"
        )
        for (instance in instances) {
            val url = runCatching { queryMirror(instance) }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun queryMirror(instanceUrl: String): String? {
        val req = Request.Builder()
            .url(instanceUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        mirrorClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)

            // Piped: { "audioStreams": [ { "url", "bitrate" } ] }
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