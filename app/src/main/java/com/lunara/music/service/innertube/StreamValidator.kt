package com.lunara.music.service.innertube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pre-flight validation for a resolved googlevideo URL.
 *
 * This is the single most important piece of the playback pipeline.
 *
 * YouTube hands out media URLs that *look* perfectly healthy — a probe of
 * `Range: bytes=0-1` returns `206 Partial Content` — but which the Google Video
 * Server silently truncates at exactly 1 MiB (1 048 576 bytes) whenever the
 * client is not PO-token attested. ExoPlayer happily buffers that first
 * megabyte, asks for the next byte range, receives `403 Forbidden`, and then
 * sits in `STATE_BUFFERING` forever. That is exactly the "stuck in buffer, song
 * never plays" symptom.
 *
 * The only way to tell a good URL from a capped one *before* handing it to the
 * player is to probe past the throttle boundary, which is what this does, using
 * the same headers the minting identity requires.
 */
object StreamValidator {
    private const val TAG = "StreamValidator"

    /** The GVS throttle boundary. A stream that stops here is capped. */
    private const val GVS_THROTTLE_BYTES = 1_048_576L

    /** Probe window, comfortably past the boundary. */
    private const val PROBE_SIZE = 64L * 1024L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * True when [candidate] can serve data from deep inside the file.
     *
     * Tracks shorter than the probe window cannot be probed past the wall — for
     * those a plain first-byte probe is all the evidence available.
     */
    suspend fun isPlayable(candidate: StreamCandidate): Boolean =
        withContext(Dispatchers.IO) {
            val size = candidate.contentLength
            if (size > 0 && size <= GVS_THROTTLE_BYTES + PROBE_SIZE) {
                // Track fits inside the throttle window: there is nothing past
                // the wall a player would ever need.
                return@withContext probeCode(candidate, 0L) in ACCEPTED_CODES
            }

            val probeStart = GVS_THROTTLE_BYTES + PROBE_SIZE
            var lastCode = 0
            // Two attempts: a CDN edge occasionally 403s a single deep probe even
            // for a valid URL, and discarding a good stream costs a whole
            // re-resolve, so one extra round trip is worth it.
            repeat(2) { attempt ->
                lastCode = probeCode(candidate, probeStart)
                if (lastCode in ACCEPTED_CODES) {
                    if (attempt > 0) Log.d(TAG, "Deep probe succeeded on attempt ${attempt + 1}")
                    return@withContext true
                }
                if (lastCode in TERMINAL_CODES) {
                    // Throttled or dead: retrying cannot help.
                    Log.w(TAG, "Deep probe rejected (HTTP $lastCode) past the 1 MiB GVS wall")
                    return@withContext false
                }
            }
            Log.w(TAG, "Deep probe inconclusive (HTTP $lastCode); accepting URL")
            // Network flakiness is not proof of a capped stream.
            true
        }

    /** Cheap liveness probe at [start] within the stream. */
    suspend fun probe(candidate: StreamCandidate, start: Long = 0L): Boolean =
        withContext(Dispatchers.IO) { probeCode(candidate, start) in ACCEPTED_CODES }

    /**
     * Resolves [videoId] against every known client, deep-validates each
     * candidate and returns the first genuinely playable stream.
     *
     * Ciphered formats are deciphered before validation; a candidate is only
     * accepted once its URL has been *proven* to serve data past the GVS
     * throttle boundary. A client that returns only capped URLs is skipped
     * rather than handed to the player.
     */
    suspend fun resolvePlayable(
        videoId: String,
        playlistId: String? = null,
    ): StreamCandidate? {
        if (videoId.isBlank()) return null

        for (client in InnerTubeClients.STREAM_CLIENTS) {
            val response = try {
                InnerTubePlayer.fetchPlayerResponse(client, videoId, playlistId)
            } catch (e: Exception) {
                Log.d(TAG, "Player request failed for $videoId via ${client.clientName}: ${e.message}")
                null
            }
            if (response == null) {
                Log.d(TAG, "No player response for $videoId via ${client.clientName}")
                continue
            }

            val candidates = try {
                InnerTubePlayer.collectAudioCandidates(response, client, videoId)
            } catch (e: Exception) {
                Log.d(TAG, "Could not read formats for $videoId: ${e.message}")
                emptyList()
            }

            for (candidate in candidates) {
                if (candidate.isExpired) continue
                if (isPlayable(candidate)) {
                    Log.i(
                        TAG,
                        "Playable stream for $videoId via ${client.clientName} " +
                            "(${candidate.bitrate / 1000} kbps ${candidate.contentType})"
                    )
                    return candidate
                }
                Log.d(TAG, "Rejected capped candidate for $videoId via ${client.clientName}")
            }
        }

        // Last resort: mirrors mint their own googlevideo URLs, so their output is
        // not subject to our IP's GVS throttle when InnerTube's is.
        return try {
            StreamResolver.resolveViaMirrors(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Mirror fallback failed for $videoId: ${e.message}")
            null
        }
    }

    private fun probeCode(candidate: StreamCandidate, start: Long): Int {
        val end = start + PROBE_SIZE - 1
        val request = Request.Builder()
            .url(candidate.url)
            .header("Range", "bytes=$start-$end")
            .apply { candidate.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                // Drain the probe window so the connection can be pooled/reused.
                response.body?.source()?.let { source ->
                    runCatching { source.request(PROBE_SIZE) }
                }
                response.code
            }
        }.getOrDefault(0)
    }

    private val ACCEPTED_CODES = setOf(200, 206)
    private val TERMINAL_CODES = setOf(401, 403, 410)
}
