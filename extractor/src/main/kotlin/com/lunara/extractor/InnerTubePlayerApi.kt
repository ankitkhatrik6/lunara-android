package com.lunara.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the InnerTube `/player` endpoint and turns its answer into [AudioStream]s.
 *
 * This layer does one job: ask, and report honestly what came back. It does not decide
 * which client is worth trying, and it does not judge whether a stream is playable —
 * that judgement needs different evidence and lives in the resolver.
 */
object InnerTubePlayerApi {

    private const val TAG = "LunaraPlayerApi"
    private const val ENDPOINT = "/youtubei/v1/player"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Deliberately patient. Every play depends on this request, and a slow answer that
     * succeeds beats a fast failure that sends us round to the next client.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** What `/player` said about the track, before any judgement is applied. */
    data class PlayerResult(
        val playable: Boolean,
        val status: String,
        val reason: String?,
        val streams: List<AudioStream>,
        val isLive: Boolean,
        val durationMs: Long,
    )

    /**
     * Asks [client] for [videoId].
     *
     * @param poToken session token, sent as `serviceIntegrityDimensions.poToken`. Without
     *   it most clients answer `LOGIN_REQUIRED` no matter what happens downstream.
     * @param signatureTimestamp required by some clients before they will return a
     *   usable `streamingData` block at all.
     */
    suspend fun fetch(
        client: ExtractorClient,
        videoId: String,
        visitorData: String?,
        poToken: String? = null,
        signatureTimestamp: Int? = null,
    ): PlayerResult? = withContext(Dispatchers.IO) {
        val body = buildString {
            append("""{"context":{"client":""")
            append(client.toClientContext(visitorData, "en", "US"))
            append("},")
            append(""""videoId":""")
            append(JSONObject.quote(videoId))
            append(""","contentCheckOk":true,"racyCheckOk":true""")
            if (signatureTimestamp != null && signatureTimestamp > 0) {
                append(""","playbackContext":{"contentPlaybackContext":{"signatureTimestamp":""")
                append(signatureTimestamp)
                append("}}")
            }
            if (!poToken.isNullOrBlank()) {
                append(""","serviceIntegrityDimensions":{"poToken":""")
                append(JSONObject.quote(poToken))
                append("}")
            }
            append("}")
        }

        val request = Request.Builder()
            .url("${client.origin}$ENDPOINT?key=${client.apiKey}&prettyPrint=false")
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", client.userAgent)
            .header("Origin", client.origin)
            .header("Referer", client.referer)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .apply {
                if (client.sendsVisitorData && !visitorData.isNullOrBlank()) {
                    header("X-Goog-Visitor-Id", visitorData)
                }
            }
            .build()

        val text = runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "${client.displayName}: HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            }
        }.getOrElse {
            Log.d(TAG, "${client.displayName}: ${it.message}")
            return@withContext null
        } ?: return@withContext null

        runCatching { parse(text, client, visitorData) }.getOrElse {
            Log.w(TAG, "Could not read ${client.displayName} response: ${it.message}")
            null
        }
    }

    private fun parse(
        body: String,
        client: ExtractorClient,
        visitorData: String?,
    ): PlayerResult {
        val root = JSONObject(body)
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty().ifBlank { "UNKNOWN" }
        val reason = playability?.optString("reason").orEmpty().ifBlank { null }

        val details = root.optJSONObject("videoDetails")
        val durationMs = details?.optString("lengthSeconds")?.toLongOrNull()?.times(1000L) ?: 0L
        val isLive = details?.optBoolean("isLive") ?: false

        // Formats are only worth reading from a response that says it can play.
        val streams = if (status == "OK") {
            collectStreams(root, client, visitorData, durationMs, isLive)
        } else {
            emptyList()
        }

        return PlayerResult(
            playable = status == "OK",
            status = status,
            reason = reason,
            streams = streams,
            isLive = isLive,
            durationMs = durationMs,
        )
    }

    private fun collectStreams(
        root: JSONObject,
        client: ExtractorClient,
        visitorData: String?,
        durationMs: Long,
        isLive: Boolean,
    ): List<AudioStream> {
        val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
        val formats = mutableListOf<JSONObject>()
        streamingData.optJSONArray("adaptiveFormats")?.let { collect(it, formats) }
        streamingData.optJSONArray("formats")?.let { collect(it, formats) }

        val headers = client.mediaHeaders(visitorData)

        // `playerConfig.audioConfig.loudnessDb` describes the video, not the format, so
        // it is read once here and attached to every candidate. A missing figure is a
        // normal answer — clients differ in whether they include it — and simply means
        // the player will not adjust volume for this track.
        val loudnessDb = root.optJSONObject("playerConfig")
            ?.optJSONObject("audioConfig")
            ?.optDouble("loudnessDb")
            ?.takeIf { !it.isNaN() }
        // `streamingData` carries an explicit lifetime. Guessing a fixed one when it is
        // absent means handing out a URL that outlives its own validity.
        val declaredExpiryMs = streamingData.optLong("expiresInSeconds")
            .takeIf { it > 0 }
            ?.times(1000L)
            ?.let { System.currentTimeMillis() + it }

        return formats
            .filter { isAudioOnly(it) }
            .mapNotNull { format ->
                // Either an address, or a signature cipher that the resolver will feed
                // through the site's own player script before anything is probed.
                // Neither present means the entry is not a stream at all.
                val url = format.optString("url").takeIf { it.isNotBlank() }
                val cipher = format.optString("signatureCipher").takeIf { it.isNotBlank() }
                    ?: format.optString("cipher").takeIf { it.isNotBlank() }
                if (url == null && cipher == null) return@mapNotNull null

                val bitrate = format.optInt("bitrate").takeIf { it > 0 }
                    ?: format.optInt("averageBitrate").takeIf { it > 0 }
                    ?: 0

                AudioStream(
                    url = url.orEmpty(),
                    itag = format.optInt("itag"),
                    mimeType = format.optString("mimeType"),
                    bitrate = bitrate,
                    contentLength = format.optString("contentLength").toLongOrNull() ?: 0L,
                    clientName = client.displayName,
                    headers = headers,
                    expiresAtMs = maxOf(AudioStream.expiryFromUrl(url.orEmpty()), declaredExpiryMs ?: 0L),
                    approxDurationMs = durationMs,
                    audioQuality = format.optString("audioQuality").takeIf { it.isNotBlank() },
                    audioChannels = format.optInt("audioChannels").coerceAtLeast(2),
                    isLiveStream = isLive,
                    signatureCipher = cipher,
                    loudnessDb = loudnessDb,
                ).withSafeLifetime()
            }
            .sortedWith(streamPreference)
    }

    private fun collect(array: org.json.JSONArray, into: MutableList<JSONObject>) {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { into.add(it) }
        }
    }

    /**
     * True for an audio-only format.
     *
     * The mime type is the decisive check: a muxed combined stream also carries no
     * width and height, and playing one of those means downloading a video to listen to
     * a song.
     */
    private fun isAudioOnly(format: JSONObject): Boolean {
        if (!format.optString("mimeType").startsWith("audio/")) return false
        return !format.has("width") && !format.has("height")
    }

    /**
     * Order streams are tried in: most widely decodable first, then best quality.
     *
     * `audio/mp4` (AAC) leads because every Android device can decode it, including
     * those whose MediaCodec cannot handle Opus in WebM — a stream the hardware cannot
     * play is worth nothing however good it sounds. Opus leads within each container
     * because at equal bitrate it is measurably better, and at ~128 kbps that is the
     * difference between acceptable and not.
     */
    private val streamPreference =
        compareByDescending<AudioStream> { it.containerMimeType == "audio/mp4" }
            .thenByDescending { it.isOpus }
            .thenByDescending { it.bitrate }

    private fun maxOf(a: Long, b: Long) = if (a >= b) a else b
}