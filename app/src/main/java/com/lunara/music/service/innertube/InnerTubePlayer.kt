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
 * Self-contained InnerTube `/player` client.
 *
 * It asks for the player response of a video id under a given client identity
 * and converts the returned audio formats into [StreamCandidate]s that carry
 * the identity, headers and expiry the later media request will need.
 *
 * Formats that arrive ciphered are deciphered here instead of being discarded,
 * which is what keeps the WEB-family clients usable as fallbacks.
 */
object InnerTubePlayer {
    private const val TAG = "InnerTubePlayer"

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
        visitorData?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        for (host in listOf(InnerTubeClients.YOUTUBE_BASE, InnerTubeClients.MUSIC_BASE)) {
            runCatching {
                val req = Request.Builder()
                    .url("$host/")
                    .addHeader("User-Agent", InnerTubeClients.USER_AGENT_WEB)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    extractVisitorData(body)?.let { visitorData = it }
                }
            }.onFailure { Log.w(TAG, "visitorData fetch failed for $host: ${it.message}") }
            if (!visitorData.isNullOrBlank()) break
        }
        visitorData
    }

    private fun extractVisitorData(body: String): String? {
        val marker = "\"visitorData\":\""
        val start = body.indexOf(marker)
        if (start == -1) return null
        val from = start + marker.length
        val end = body.indexOf('"', from)
        return if (end > from) body.substring(from, end) else null
    }

    /**
     * Request the player response for [videoId] using [client]. Returns null when
     * the request fails or the video is not playable.
     */
    suspend fun fetchPlayerResponse(
        client: InnerTubeClient,
        videoId: String,
        playlistId: String? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val visitor = YouTubeSession.visitorData ?: ensureVisitorData()

        val context = JSONObject().apply {
            put("client", client.toClientContext(visitor, hl = "en", gl = "US"))
        }

        val playbackContext = JSONObject().put(
            "contentPlaybackContext", JSONObject().apply {
                put("html5Preference", "HTML5_PREF_WANTS")
                put("signatureVoiceSearch", false)
            }
        )

        val body = JSONObject().apply {
            put("context", context)
            put("videoId", videoId)
            if (!playlistId.isNullOrBlank()) put("playlistId", playlistId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("playbackContext", playbackContext)
        }

        val url = "${client.baseUrl}/youtubei/v1/player?key=${client.apiKey}&prettyPrint=false"
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Format-Version", "1")
            .addHeader("X-Goog-Api-Key", client.apiKey)
            .addHeader("X-YouTube-Client-Name", client.clientId)
            .addHeader("X-YouTube-Client-Version", client.clientVersion)
            .addHeader("Origin", client.origin)
            .addHeader("Referer", client.referer)
            .addHeader("User-Agent", client.userAgent)
            .apply { visitor?.let { addHeader("X-Goog-Visitor-Id", it) } }
            .apply { attachSession(this, client.origin) }
            .build()

        executePlayer(req, client, videoId)
    }

    private suspend fun executePlayer(
        req: Request,
        client: InnerTubeClient,
        videoId: String,
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.d(TAG, "${client.clientName}: HTTP ${r.code} for $videoId")
                    return@withContext null
                }
                val text = r.body?.string() ?: return@withContext null
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                val playability = json.optJSONObject("playabilityStatus")
                val status = playability?.optString("status")
                if (status != null && status != "OK") {
                    Log.d(TAG, "${client.clientName}: playability=$status " +
                        "(${playability?.optString("reason")}) for $videoId")
                    return@withContext null
                }
                InnerTubeSession.rememberFromResponse(json)
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "${client.clientName}: request failed for $videoId: ${e.message}")
            null
        }
    }

    private fun attachSession(builder: Request.Builder, origin: String) {
        YouTubeSession.cookie.value?.let { cookie ->
            builder.header("Cookie", cookie)
            YouTubeSession.authorizationHeader(origin)?.let { auth ->
                builder.header("Authorization", auth)
            }
        }
    }

    /**
     * Every audio format of [playerResponse], best first, as playable candidates
     * bound to [client]'s identity.
     *
     * Direct URLs are used as-is; ciphered ones are deciphered through
     * [PlayerCipher]. The `n` throttling parameter, when present, is run
     * through [NParameterTransformer]. A format whose URL cannot be recovered is
     * skipped rather than silently returned in a state that is guaranteed to
     * fail at playback time.
     */
    suspend fun collectAudioCandidates(
        playerResponse: JSONObject,
        client: InnerTubeClient,
        videoId: String,
    ): List<StreamCandidate> {
        val streaming = playerResponse.optJSONObject("streamingData") ?: return emptyList()

        val formats = mutableListOf<JSONObject>()
        streaming.optJSONArray("adaptiveFormats")?.let { addAll(it, formats) }
        streaming.optJSONArray("formats")?.let { addAll(it, formats) }

        val headers = client.mediaHeaders()
        val expiresAt = System.currentTimeMillis() + DEFAULT_URL_LIFETIME_MS

        return formats
            .filter { it.isAudioFormat() }
            .mapNotNull { format ->
                val bitrate = format.optInt("bitrate", 0).let {
                    if (it > 0) it else format.optInt("averageBitrate", 0)
                }
                val mimeType = format.optString("mimeType", "")
                val contentLength = format.optString("contentLength", "").toLongOrNull() ?: 0L

                val rawUrl = format.optString("url", "").takeIf { it.isNotBlank() }
                    ?: run {
                        val cipher = format.optString("signatureCipher", "")
                            .ifBlank { format.optString("cipher", "") }
                        if (cipher.isBlank()) return@mapNotNull null
                        PlayerCipher.decipherSignatureCipher(cipher)
                    }
                    ?: return@mapNotNull null

                // Fix the throttling parameter before the URL is ever requested.
                val fixed = NParameterTransformer.apply(rawUrl) { value ->
                    PlayerCipher.transformNParameter(value)
                }
                if (fixed != rawUrl) {
                    Log.d(TAG, "Rewrote throttling parameter for $videoId")
                }

                StreamCandidate(
                    url = fixed,
                    mimeType = mimeType,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    clientName = client.clientName,
                    headers = headers,
                    expiresAtMs = StreamCandidate.expiryFromUrl(fixed)
                        .takeIf { it > 0 } ?: expiresAt,
                    source = "innertube",
                ).withSafeLifetime()
            }
            .sortedWith(
                compareByDescending<StreamCandidate> { it.contentType == "audio/mp4" }
                    .thenByDescending { it.bitrate }
            )
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

    /** Fallback lifetime for responses that omit the `expire` parameter. */
    private const val DEFAULT_URL_LIFETIME_MS = 5 * 60 * 60 * 1000L
}
