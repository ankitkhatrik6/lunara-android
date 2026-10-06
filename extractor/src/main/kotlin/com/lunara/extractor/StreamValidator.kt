package com.lunara.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Decides whether a resolved URL will actually play, and — just as importantly —
 * whether rejecting it would cost a working stream.
 *
 * The trap this exists to avoid
 * ----------------------------
 * A live probe of a resolving `IOS` stream produced exactly this:
 *
 * ```
 * range@0         -> 206, 65536 bytes
 * range@1048576   -> 403            <- capped at exactly 1 MiB
 * range@2097152   -> 403
 * ```
 *
 * It is tempting to read that as "the stream is capped" and refuse it. But the same
 * probe shows the first byte arrives fine, and a `403` on a *deep* range is equally
 * produced by a CDN edge that has not yet seen this particular byte range, by a
 * network that drops the second connection of a pair, and by genuinely capped streams.
 * Treating it as proof rejects streams that would have played, which is how a player
 * ends up resolving nothing at all and showing "couldn't play this song" for a whole
 * library.
 *
 * So the rule here is deliberately narrow: **a first-byte failure rejects, a deep
 * failure does not.**
 *
 * - The first bytes are what ExoPlayer needs to even begin. If they will not come, the
 *   stream is dead and rejecting it saves the user a silent wait.
 * - Everything past that is the player's problem to discover, and it is far better at
 *   it than a speculative probe made from a different connection.
 *
 * A caller that wants stronger evidence can ask [isDeeplyReadable] explicitly: the
 * resolver uses it to hand the player a URL proven to serve data past the 1 MiB wall
 * (a capped URL plays ~64s and then buffer-loops), and downloads use it to refuse a
 * file that would be silently truncated. [isCapped] distinguishes a terminal refusal
 * from an inconclusive probe for those callers.
 */
object StreamValidator {

    private const val TAG = "StreamValidator"

    /**
     * The GVS throttle boundary, 1 MiB. Only used to describe a failure, never to
     * reject on its own.
     */
    private const val GVS_THROTTLE_BYTES = 1_048_576L

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Whether [stream] can serve its first bytes to the player.
     *
     * This is the only check the playback path performs, and it is intentionally a
     * cheap one. See the class comment for why a deep probe must not gate playback.
     */
    suspend fun isPlayable(stream: AudioStream): Boolean = withContext(Dispatchers.IO) {
        // A stream whose URL has already expired is dead by definition and needs no
        // request to prove it.
        if (stream.isExpired) {
            Log.d(TAG, "Stream for ${stream.clientName} has already expired")
            return@withContext false
        }
        val code = probeCode(stream, start = 0L, length = FIRST_PROBE_BYTES)
        when (code) {
            in ACCEPTED_CODES -> true
            // 403/410 on the very first bytes is the CDN refusing this identity, and
            // no amount of buffering will change that.
            in TERMINAL_CODES -> {
                Log.w(TAG, "${stream.clientName} refused the stream outright (HTTP $code)")
                false
            }
            else -> {
                // A timeout or a dropped connection is not evidence of a bad URL. The
                // stream is handed to the player, which retries on its own schedule and
                // with its own connection reuse.
                Log.d(TAG, "First-byte probe inconclusive for ${stream.clientName}; letting the player try")
                true
            }
        }
    }

    /**
     * Whether [stream] can serve data from well past the throttle boundary.
     *
     * Exposed for diagnostics and for callers that genuinely want certainty — for
     * example when writing a file to disk, where a silent truncation would be worse
     * than a failed download. The resolver also uses it to prefer streams proven
     * to play past ~64s (two 512 KiB chunks) over first-byte-only ones. An
     * inconclusive probe (timeout, dropped connection) still returns true so a
     * flaky network never rejects a working stream; use [isCapped] when a terminal
     * refusal must be distinguished from inconclusive.
     */
    suspend fun isDeeplyReadable(stream: AudioStream): Boolean = withContext(Dispatchers.IO) {
        // A track shorter than the probe window has nothing past the boundary a player
        // would ever reach, so the first-byte probe is the only evidence available.
        if (stream.contentLength in 1..(GVS_THROTTLE_BYTES + DEEP_PROBE_BYTES)) {
            return@withContext probeCode(stream, 0L, FIRST_PROBE_BYTES) in ACCEPTED_CODES
        }
        val start = GVS_THROTTLE_BYTES + DEEP_PROBE_BYTES
        probeCode(stream, start, DEEP_PROBE_BYTES) in ACCEPTED_CODES
    }

    /**
     * True only when the CDN terminally refuses (401/403/410) past the 1 MiB throttle
     * wall: the signature of a capped URL that plays ~64s and then buffer-loops.
     * Timeouts and dropped connections return false — inconclusive, not capped.
     */
    suspend fun isCapped(stream: AudioStream): Boolean = withContext(Dispatchers.IO) {
        if (stream.contentLength in 1..(GVS_THROTTLE_BYTES + DEEP_PROBE_BYTES)) return@withContext false
        val start = GVS_THROTTLE_BYTES + DEEP_PROBE_BYTES
        probeCode(stream, start, DEEP_PROBE_BYTES) in TERMINAL_CODES
    }

    /** A single ranged read of [length] bytes at [start], using the stream's own identity. */
    private fun probeCode(stream: AudioStream, start: Long, length: Long): Int {
        val request = Request.Builder()
            .url(stream.url)
            .header("Range", "bytes=$start-${start + length - 1}")
            // The minting identity has to be present even on a probe: a request that
            // looks anonymous is refused before the range is ever considered.
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                // Read the window, then close, so the connection returns to the pool
                // instead of being torn down on every probe.
                response.body?.source()?.let { source -> runCatching { source.request(length) } }
                response.code
            }
        }.getOrDefault(0)
    }

    /** Small enough to be near-free, large enough that the server has to answer. */
    private const val FIRST_PROBE_BYTES = 32L * 1024L

    /** Comfortably past the 1 MiB boundary. */
    private const val DEEP_PROBE_BYTES = 64L * 1024L

    private val ACCEPTED_CODES = setOf(200, 206)
    private val TERMINAL_CODES = setOf(401, 403, 410)
}