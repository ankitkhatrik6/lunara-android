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
        for (host in listOf(InnerTubeClients.YOUTUBE_BASE, InnerTubeClients.MUSIC_BASE)) {
            runCatching {
                val req = Request.Builder()
                    .url("$host/")
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
            }.onFailure { Log.w(TAG, "visitorData fetch failed for $host: ${it.message}") }
            if (!visitorData.isNullOrBlank()) break
        }
        visitorData
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

        // Blazify: web-family requests carry playbackContext; sts (which
        // player.js generation is asking) is quoted when the client needs it.
        val playbackContext = JSONObject().put(
            "contentPlaybackContext", JSONObject().apply {
                put("html5Preference", "HTML5_PREF_WANTS")
                put("signatureVoiceSearch", false)
            }
        )
        if (client.useSignatureTimestamp) {
            PlayerCipher.signatureTimestamp()?.let { playbackContext.put("signatureTimestamp", it) }
        }
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
            .addHeader("X-Origin", client.origin)
            .addHeader("Referer", client.referer)
            .addHeader("User-Agent", client.userAgent)
            .apply { visitor?.let { addHeader("X-Goog-Visitor-Id", it) } }
            .apply { attachSession(this, client.origin) }
            .build()

        postPlayer(url, req, client, videoId)
    }

    private suspend fun postPlayer(
        url: String,
        req: Request,
        client: InnerTubeClient,
        videoId: String,
    ): JSONObject? = withContext(Dispatchers.IO) {

        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "${client.clientName}: HTTP ${r.code} for $videoId")
                    return@withContext alternateIfMusicHost(url, req, client, videoId, r.code)
                }
                val text = r.body?.string() ?: return@withContext null
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status != null && status != "OK") {
                    Log.d(TAG, "${client.clientName}: playability=$status for $videoId")
                    if (status == "LOGIN_REQUIRED" || status == "UNPLAYABLE" || status == "ERROR") {
                        return@withContext alternateIfMusicHost(url, req, client, videoId, null)
                    }
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

    /**
     * music.youtube.com rejects some third-party clients that work fine on
     * www.youtube.com (seen as LOGIN_REQUIRED / HTTP errors). Retry once on the
     * alternate host before the resolver falls through to the next client.
     */
    private suspend fun alternateIfMusicHost(
        url: String,
        req: Request,
        client: InnerTubeClient,
        videoId: String,
        failedCode: Int?,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val alternateBase = when {
            url.startsWith(InnerTubeClients.MUSIC_BASE) -> InnerTubeClients.YOUTUBE_BASE
            url.startsWith(InnerTubeClients.YOUTUBE_BASE) -> InnerTubeClients.MUSIC_BASE
            else -> return@withContext null
        }
        val alternateUrl = url.replaceFirst(
            if (alternateBase == InnerTubeClients.YOUTUBE_BASE) InnerTubeClients.MUSIC_BASE else InnerTubeClients.YOUTUBE_BASE,
            alternateBase
        )
        try {
            http.newCall(req.newBuilder().url(alternateUrl).build()).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "${client.clientName}: alternate host HTTP ${r.code} for $videoId")
                    return@withContext null
                }
                val text = r.body?.string() ?: return@withContext null
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status != "OK") {
                    Log.d(TAG, "${client.clientName}: alternate host playability=$status for $videoId")
                    return@withContext null
                }
                InnerTubeSession.rememberFromResponse(json)
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "${client.clientName}: alternate host failed for $videoId: ${e.message}")
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

    data class AudioCandidate(
        val url: String,
        val bitrate: Int,
        val isAac: Boolean,
        val isOriginal: Boolean,
        val signatureCipher: String? = null,
    )

    fun collectAudioCandidates(playerResponse: JSONObject): List<AudioCandidate> {
        val streaming = playerResponse.optJSONObject("streamingData") ?: return emptyList()

        val candidates = mutableListOf<JSONObject>()
        streaming.optJSONArray("adaptiveFormats")?.let { addAll(it, candidates) }
        streaming.optJSONArray("formats")?.let { addAll(it, candidates) }

        return candidates
            .filter { it.isAudioFormat() }
            .mapNotNull { format ->
                // Direct URL first; otherwise keep the cipher for later
                // deciphering (Blazify: signatureCipher -> deciphered URL).
                val url = format.optString("url", "").takeIf { it.isNotBlank() }
                if (url != null) {
                    AudioCandidate(
                        url = url,
                        bitrate = format.optInt("bitrate", 0).let {
                            if (it > 0) it else format.optInt("averageBitrate", 0)
                        },
                        isAac = format.optString("mimeType").contains("mp4a"),
                        isOriginal = format.isOriginalAudio()
                    )
                } else {
                    val cipher = format.optString("signatureCipher", "")
                        .ifBlank { format.optString("cipher", "") }
                        .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AudioCandidate(
                        url = "",
                        bitrate = format.optInt("bitrate", 0).let {
                            if (it > 0) it else format.optInt("averageBitrate", 0)
                        },
                        isAac = format.optString("mimeType").contains("mp4a"),
                        isOriginal = format.isOriginalAudio(),
                        signatureCipher = cipher
                    )
                }
            }
            .sortedWith(
                compareByDescending<AudioCandidate> { it.isOriginal }
                    .thenByDescending { it.bitrate }
                    .thenByDescending { if (it.isAac) 1 else 0 }
            )
    }

    /**
     * Picks the best directly playable audio URL from a player response.
     * Returns null when no audio format carries a plain URL (those would need
     * signature deobfuscation, which the chosen client identities avoid).
     */
    fun selectAudioUrl(playerResponse: JSONObject): String? {
        val candidates = collectAudioCandidates(playerResponse)
        val best = candidates.firstOrNull() ?: return null
        return best.url.takeIf { it.isNotBlank() }
    }

    suspend fun resolveBestUrl(
        playerResponse: JSONObject,
        validate: suspend (String) -> Boolean = { validateUrl(it) },
    ): String? {
        for (candidate in collectAudioCandidates(playerResponse)) {
            if (candidate.url.isNotBlank()) {
                if (validate(candidate.url)) return candidate.url
                continue
            }
            val cipher = candidate.signatureCipher ?: continue
            val deciphered = runCatching { PlayerCipher.decipherSignatureCipher(cipher) }.getOrNull()
            if (!deciphered.isNullOrBlank() && validate(deciphered)) return deciphered
        }
        return null
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