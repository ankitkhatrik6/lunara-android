package com.lunara.music.service.audio

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.lunara.extractor.AudioStream
import com.lunara.extractor.ResolveFailure
import com.lunara.extractor.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * The size of one network load, in bytes.
 *
 * The player is never given a googlevideo URL to hold open for a whole song. Every
 * load asks the resolver for an address and takes at most this many bytes from it,
 * then comes back. That is the same chunking Blazify (and the InnerTune family
 * before it) streams with, and it is what turns the failure modes of a single
 * long-lived connection into non-events:
 *
 *  - A URL that expires, gets capped at 1 MiB, or is refused on a later range
 *    request only kills *one* chunk: the next open resolves a fresh URL while the
 *    buffer still holds tens of seconds of audio.
 *  - A connection the CDN drops mid-transfer costs one chunk; the recovery below
 *    resumes from the exact byte offset on a new connection.
 *  - Identity headers are re-attached at every open, so a registry eviction can
 *    never leave a live request without them.
 *
 * 512 KiB is ~30 seconds of a 128 kbps song — large enough that chunk boundaries
 * are rare, small enough that recovery work is bounded.
 */
private const val STREAM_CHUNK_LENGTH = 512L * 1024L

/** HTTP codes that mean "this address is dead", as opposed to "this connection failed". */
private val REFUSED_CODES = setOf(401, 403, 410)

/**
 * Resolves stream URLs at request time and reads them in fixed-size chunks.
 *
 * This is Lunara's counterpart of Blazify's `ResolvingDataSource` pipeline. The
 * player's `MediaItem` for an online song carries the **video id** (no scheme) and
 * the song id as its custom cache key; a literal `file://`, `content://` or `http(s)`
 * URI passes straight through.
 *
 * For a video id, every [open] runs the same three steps:
 *
 *  1. Resolve (through [StreamResolver], whose cache makes this instant in the
 *     common case and whose lock makes concurrent opens for one track share work).
 *     A refusal becomes an [IOException] with a message a human can act on.
 *  2. Bind the minting identity's headers to the URL so the OkHttp interceptor
 *     sends them on every range request.
 *  3. Open the delegate with the URL capped at [STREAM_CHUNK_LENGTH] bytes via
 *     [DataSpec.subrange], so the load ends cleanly and the *next* open gets a
 *     fresh resolution decision.
 *
 * Failures heal in place instead of restarting the song:
 *
 *  - A refused address at open time (401/403/410 — expired or capped) drops the
 *    resolver's cache entry and retries once with a freshly minted URL.
 *  - A connection that dies mid-chunk reopens on a new connection **at the exact
 *    byte offset** and keeps filling the same buffer. The player never notices.
 *
 * Resolving blocks the caller, which is the player's loading thread — never the UI
 * thread. That is deliberate and matches Blazify: the buffer is what pays for the
 * lookup, and the lookup has to come back before the load can.
 */
class StreamResolvingDataSource private constructor(
    private val delegate: DataSource,
) : DataSource {

    /** The schemeless spec this load was opened with; the source of truth for recovery. */
    private var baseSpec: DataSpec? = null

    /** The track being read, or null when the load was a pass-through (local file, live URL). */
    private var videoId: String? = null

    /** Absolute position of the current chunk, and how much of it has been read. */
    private var chunkStart = 0L
    private var chunkBytesRead = 0L

    /** One in-place recovery per open; a second failure belongs to the player's retry policy. */
    private var recoveredDuringRead = false

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec

        // A real address (local file, MediaStore item, live playlist, pre-resolved URL)
        // has nothing to look up. Hand it to the delegate untouched.
        if (dataSpec.uri.scheme != null) {
            videoId = null
            return delegate.open(dataSpec)
        }

        val id = dataSpec.key?.takeIf { it.isNotBlank() } ?: dataSpec.uri.toString()
        videoId = id
        recoveredDuringRead = false

        var attempt = 0
        while (true) {
            val stream = resolveStream(id)
            StreamHeaders.register(stream.url, stream.headers)
            try {
                val bytes = delegate.open(chunkSpecFor(dataSpec, stream.url))
                chunkStart = dataSpec.position + dataSpec.uriPositionOffset
                chunkBytesRead = 0
                return bytes
            } catch (e: IOException) {
                // A refusal means this URL is dead (expired, capped at 1 MiB, or the
                // identity moved on); anything else is a connection that may yet work
                // on a second try. Either way the retry is one bounded round trip.
                val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val refused = code != null && code in REFUSED_CODES
                if (refused) {
                    Log.w(TAG, "CDN refused $id (HTTP $code); re-resolving")
                    StreamResolver.invalidate(id)
                }
                if (attempt < 1) {
                    attempt += 1
                    continue
                }
                throw e
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) chunkBytesRead += read
            return read
        } catch (e: IOException) {
            // The address is fine — the connection under it is not. Reopen on a fresh
            // connection at the exact byte offset so the buffer keeps growing from
            // where it stopped instead of the song starting over.
            val id = videoId ?: throw e
            val spec = baseSpec ?: throw e
            if (recoveredDuringRead) throw e
            recoveredDuringRead = true

            val resumeAt = chunkStart + chunkBytesRead
            Log.w(TAG, "Connection for $id died at byte $resumeAt; reconnecting in place", e)
            runCatching { delegate.close() }

            val stream = resolveStream(id)
            StreamHeaders.register(stream.url, stream.headers)
            delegate.open(
                spec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setPosition(resumeAt)
                    .setLength(STREAM_CHUNK_LENGTH)
                    .build(),
            )
            chunkStart = resumeAt
            chunkBytesRead = 0
            return delegate.read(buffer, offset, length)
        }
    }

    override fun getUri(): Uri? = delegate.getUri() ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        try {
            delegate.close()
        } finally {
            baseSpec = null
            videoId = null
            chunkStart = 0L
            chunkBytesRead = 0L
            recoveredDuringRead = false
        }
    }
    /**
     * Resolves [videoId] on the loading thread, bounded.
     *
     * The bound matters: a lookup that never answers would otherwise leave the player
     * buffering forever, which is the failure this whole class exists to prevent.
     * [StreamResolver] has its own inner budget; this one keeps that honest.
     */
    private fun resolveStream(videoId: String): AudioStream {
        val outcome = runBlocking {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
        } ?: throw IOException(
            "Taking longer than expected to reach YouTube. Check your connection and retry.",
        )
        return when (outcome) {
            is StreamResolver.Outcome.Success -> outcome.stream
            is StreamResolver.Outcome.Failure -> {
                Log.w(TAG, "Resolve failed for $videoId: ${outcome.reason}")
                throw IOException(outcome.reason.toUserMessage())
            }
        }
    }

    companion object {
        private const val TAG = "StreamResolvingDS"

        /** A whole resolve must not outrun the buffer that is paying for it. */
        private const val RESOLVE_TIMEOUT_MS = 20_000L

        /**
         * Rewrites [dataSpec] as a request for the first 512 KiB of [streamUrl],
         * keeping position, offset and key. Internal so the chunking contract
         * is unit-testable without a network.
         *
         * Note on [DataSpec.subrange]: its offset is *relative* to the spec's own
         * position, and the new spec's position shifts by that same offset — so
         * `subrange(position, CHUNK)` would double-count. A zero-relative-offset
         * call capped at the chunk length keeps the original position intact.
         */
        internal fun chunkSpecFor(dataSpec: DataSpec, streamUrl: String): DataSpec =
            dataSpec
                .withUri(Uri.parse(streamUrl))
                .subrange(/* offset = */ 0L, STREAM_CHUNK_LENGTH)
    }

    /** Creates instances bound to one upstream chain; each data source is single-use per load. */
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            StreamResolvingDataSource(upstream.createDataSource())
    }
}

/**
 * The user-facing meaning of a resolution failure.
 *
 * Shared by [LunaraPlayerManager] (which shows it before playback starts) and the
 * data source (which surfaces it when a mid-song re-resolve is refused): "this track
 * is unavailable" and "the network is being awkward" are different problems, and one
 * message for both is how a broken app passes for a working one.
 */
internal fun ResolveFailure.toUserMessage(): String = when (this) {
    is ResolveFailure.Unavailable -> "This song isn't available on YouTube Music"
    is ResolveFailure.Blocked -> "YouTube is rate-limiting this device. Try again shortly."
    is ResolveFailure.NoPlayableStream -> "YouTube throttled the stream. Try again in a moment."
    is ResolveFailure.Network -> "No connection to YouTube. Check your network."
}