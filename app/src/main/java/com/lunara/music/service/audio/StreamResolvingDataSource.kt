package com.lunara.music.service.audio

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import com.lunara.extractor.AudioStream
import com.lunara.extractor.ResolveFailure
import com.lunara.extractor.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private const val STREAM_CHUNK_LENGTH = 512L * 1024L
private val REFUSED_CODES = setOf(401, 403, 410)
private const val RANGE_NOT_SATISFIED = 416

class StreamResolvingDataSource(
    private val remoteUpstream: DataSource.Factory,
    private val localUpstream: DataSource.Factory,
    private val streamCache: Cache?,
) : DataSource {

    private var current: DataSource? = null
    private var currentKey: String? = null
    private var currentUrl: String? = null
    private var currentStream: AudioStream? = null
    private var currentCache: Cache? = null
    private var offsetLocked = 0L

    private var lastResolvedPosition = -1L
    private var lastResolvedBuffered = -1L

    override fun open(dataSpec: DataSpec): DataSource {
        val spec = dataSpec
        if (spec.uri.scheme != null) {
            return localUpstream.createDataSource().apply { open(spec) }
        }

        val videoId = spec.key ?: spec.uri.lastPathSegment ?: spec.uri.toString()

        if (spec.position == 0L) {
            val cachedRange = streamCache?.getFileLength(videoId) ?: 0L
            if (cachedRange > 0L && cachedRange >= spec.position + spec.length) {
                val cacheSpec = spec
                    .withUri(Uri.parse(videoId))
                    .withKey(videoId)
                    .withPosition(0L)
                    .withLength(cachedRange)
                val cacheDs = streamCache?.openInputStream(cacheSpec)?.let { ds ->
                    object : DataSource() {
                        override fun openTypedDataListener(listener: TransferListener): DataSource =
                            this@StreamResolvingDataSource.openTypedDataListener(listener)
                        override fun close() {}
                        override val error: IOException? get() = null
                        override val position: Long get() = -1L
                        override val length: Long get() = -1L
                        override val cacheKey: String? get() = videoId
                        override val uri: Uri get() = Uri.parse(videoId)
                    }
                }
                if (cacheDs != null) return cacheDs
            }
        }

        val stream = resolveStream(videoId)
        return stream.let { s ->
            currentStream = s
            currentUrl = s.url
            currentKey = videoId
            currentCache = streamCache

            if (spec.position == 0L && spec.length > 0L) {
                val cache = currentCache
                if (cache != null) {
                    val fileLen = cache.getFileLength(videoId) ?: 0L
                    if (fileLen >= spec.position + spec.length) {
                        val cacheSpec =
                            spec
                                .withUri(Uri.parse(s.url))
                                .withKey(videoId)
                                .withPosition(0L)
                                .withLength(fileLen.coerceAtMost(spec.length))
                        return cache.openInputStream(cacheSpec)
                    }
                }
            }

            val chunkSpec = chunkSpecFor(spec, s.url)
            val ds = remoteUpstream.createDataSource().apply { open(chunkSpec) }
            current = ds
            ds
        }
    }

    private fun resolveStream(videoId: String): AudioStream {
        val outcome = runBlocking {
            withTimeoutOrNull(STREAM_RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
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

    override fun openTypedDataListener(listener: TransferListener): DataSource {
        current?.let { ds ->
            val wrapped = object : DataSource() {
                override fun openTypedDataListener(l: TransferListener): DataSource = ds.openTypedDataListener(l)
                override fun close() { ds.close() }
                override val error: IOException? get() = ds.error
                override val position: Long get() = ds.position
                override val length: Long get() = ds.length
                override val cacheKey: String? get() = ds.cacheKey
                override val uri: Uri get() = ds.uri
            }
            return wrapped
        }
        return this
    }

    override fun close() {
        current?.close()
        current = null
    }

    override val error: IOException? get() = current?.error
    override val position: Long get() = current?.position ?: -1L
    override val length: Long get() = current?.length ?: -1L
    override val cacheKey: String? get() = currentKey
    override val uri: Uri get() = current?.uri ?: Uri.parse(currentUrl ?: "")

    companion object {
        private const val TAG = "StreamResolvingDS"
        private const val STREAM_RESOLVE_TIMEOUT_MS = 20_000L
    }

    /**
     * Reopen the data source at [offset], releasing any buffered input.
     *
     * A refused range that starts past the buffer's last confirmed byte is a
     * transient CDN behaviour, not an end of stream: the CDN edge simply has not
     * seen those bytes yet. Releasing the input forces a fresh connection to the
     * same address at the exact offset, which is where the evidence lives.
     */
    fun reopenAt(offset: Long) {
        if (offsetLocked == offset) return
        offsetLocked = offset
        lastResolvedPosition = -1L
        lastResolvedBuffered = -1L
        current?.close()
        current = null
    }

    /**
     * Replace the current source with a fresh URL for the same stream.
     *
     * Used when the first chunk was refused, expired or capped: the player's
     * buffer already holds tens of seconds of audio, so we simply hand it a new
     * address and continue without an interruption.
     */
    fun refreshStream(videoId: String, stream: AudioStream) {
        currentStream = stream
        currentUrl = stream.url
        currentKey = videoId
        currentCache = streamCache
        StreamHeaders.register(stream.url, stream.headers)
        // Release the previous source so its connection is torn down and cannot
        // keep a dead address alive for the next load.
        current?.close()
        current = null
        lastResolvedPosition = -1L
        lastResolvedBuffered = -1L
    }

    internal fun chunkSpecFor(dataSpec: DataSpec, streamUrl: String): DataSpec =
        dataSpec
            .withUri(Uri.parse(streamUrl))
            .subrange(/* offset = */ 0L, STREAM_CHUNK_LENGTH)
}

/** Creates instances bound to one upstream pair; each data source is single-use per load. */
class StreamResolvingDataSourceFactory(
    private val remoteUpstream: DataSource.Factory,
    private val localUpstream: DataSource.Factory = remoteUpstream,
    private val streamCache: Cache? = null,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        StreamResolvingDataSource(remoteUpstream, localUpstream, streamCache)
}

}
