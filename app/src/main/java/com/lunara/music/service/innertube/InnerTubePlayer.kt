package com.lunara.music.service.innertube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal, self contained InnerTube player client.
 *
 * It requests the `/player` endpoint for a video id, reads the adaptive audio
 * formats and returns a directly playable URL. Only formats that already carry
 * a plain `url` are accepted, so no signature deobfuscation (JavaScript) is
 * required for the client identities used here.
 */
object InnerTubePlayer {
    private const val TAG = "InnerTubePlayer"
    private const val BASE = "https://music.youtube.com/youtubei/v1"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var visitorData: String? = null

    /** Fetch and cache the InnerTube visitor id, required by some clients. */
    suspend fun ensureVisitorData(): String? = withContext(Dispatchers.IO) {
        visitorData?.let { return@withContext it }
        runCatching {
            val req = Request.Builder()
                .url("https://music.youtube.com/")
                .addHeader("User-Agent", InnerTubeClients.USER_AGENT_WEB)
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@use
                val marker = "\"visitorData\":\""
                val start = body.indexOf(marker)
                if (start != -1) {
                    val from = start + marker.length
                    val end = body.indexOf('"', from)
                    if (end > from) visitorData = body.substring(from, end)
                }
            }
        }.onFailure { Log.w(TAG, "visitorData fetch failed: ${it.message}") }
        visitorData
    }

    /**
     * Request the player response for [videoId] using [client]. Returns null when
     * the request fails or the video is not playable.
     */
    suspend fun fetchPlayerResponse(
        client: InnerTubeClient,
        videoId: String,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val visitor = ensureVisitorData()

        val context = JSONObject().apply {
            put("client", client.toClientContext(visitor, hl = "en", gl = "US"))
            if (client.isEmbedded) {
                put(
                    "thirdParty",
                    JSONObject().put("embedUrl", "https://www.youtube.com/watch?v=$videoId")
                )
            }
        }

        val body = JSONObject().apply {
            put("context", context)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val url = "$BASE/player?key=${client.apiKey}&prettyPrint=false"
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .addHeader("X-Goog-Api-Format-Version", "1")
            .addHeader("X-YouTube-Client-Name", client.clientId)
            .addHeader("X-YouTube-Client-Version", client.clientVersion)
            .addHeader("X-Origin", "https://music.youtube.com")
            .addHeader("Referer", "https://music.youtube.com/")
            .addHeader("User-Agent", client.userAgent)
            .apply { visitor?.let { addHeader("X-Goog-Visitor-Id", it) } }
            .build()

        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "${client.clientName}: HTTP ${r.code} for $videoId")
                    return@withContext null
                }
                val text = r.body?.string() ?: return@withContext null
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status != null && status != "OK") {
                    Log.d(TAG, "${client.clientName}: playability=$status for $videoId")
                    return@withContext null
                }
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "${client.clientName}: request failed for $videoId: ${e.message}")
            null
        }
    }

    /**
     * Picks the best directly playable audio URL from a player response.
     * Returns null when no audio format carries a plain URL (those would need
     * signature deobfuscation, which the chosen client identities avoid).
     */
    fun selectAudioUrl(playerResponse: JSONObject): String? {
        val streaming = playerResponse.optJSONObject("streamingData") ?: return null

        val candidates = mutableListOf<JSONObject>()
        streaming.optJSONArray("adaptiveFormats")?.let { addAll(it, candidates) }
        streaming.optJSONArray("formats")?.let { addAll(it, candidates) }

        val playable = candidates
            .filter { it.isAudioFormat() }
            .filter { !it.optString("url", "").isBlank() }
        if (playable.isEmpty()) return null

        // Prefer original (non auto-dubbed) audio, then highest bitrate, then AAC
        // for the widest codec support.
        val best = playable
            .filter { it.isOriginalAudio() }
            .ifEmpty { playable }
            .maxWithOrNull(
                compareBy<JSONObject> { it.optInt("bitrate", 0) }
                    .thenBy { if (it.optString("mimeType").contains("mp4a")) 1 else 0 }
            )
            ?: return null

        return best.optString("url", "").takeIf { it.isNotBlank() }
    }

    private fun addAll(array: JSONArray, into: MutableList<JSONObject>) {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { into.add(it) }
        }
    }

    private fun JSONObject.isAudioFormat(): Boolean {
        // Audio-only formats carry no width/height and an audio mime type.
        if (!isNull("width") || !isNull("height")) return false
        return optString("mimeType", "").startsWith("audio/")
    }

    private fun JSONObject.isOriginalAudio(): Boolean {
        val track = optJSONObject("audioTrack") ?: return true
        return !track.optBoolean("isAutoDubbed", false)
    }

    /** Confirms the URL actually serves audio (some URLs 403 on first play). */
    fun validateUrl(url: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .addHeader("User-Agent", InnerTubeClients.USER_AGENT_WEB)
            .build()
        http.newCall(req).execute().use { r ->
            r.code == 200 || r.code == 206
        }
    }.getOrDefault(false)
}