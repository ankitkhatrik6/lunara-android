package com.lunara.music.service.audio

import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping a stream URL to the HTTP headers it must be requested with.
 *
 * A googlevideo URL is only served to the same InnerTube identity that minted
 * it, so the headers that produced the URL have to travel with every media
 * request. [LunaraPlayerManager] registers them the moment a stream resolves and
 * the data source looks them up per request.
 *
 * Entries are removed once playback moves on, and the map is bounded so a long
 * listening session cannot grow it without limit.
 */
object StreamHeaders {
    private const val MAX_ENTRIES = 16

    private val headersByUrl = ConcurrentHashMap<String, Map<String, String>>()

    /** Binds [url] to [headers] for as long as the current item is playing. */
    fun register(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        if (headersByUrl.size >= MAX_ENTRIES) {
            headersByUrl.keys.firstOrNull()?.let { headersByUrl.remove(it) }
        }
        headersByUrl[url] = headers
    }

    fun lookup(url: String): Map<String, String> = headersByUrl[url].orEmpty()

    fun clear() = headersByUrl.clear()
}

/**
 * A [DataSource.Factory] that decorates the upstream factory's data sources so
 * each one carries the headers registered for the URI it is about to open.
 *
 * The URI is matched on its string form because Media3 hands the data source
 * exactly the URI that was set on the `MediaItem`, which is the URL the
 * resolver produced.
 */
class HeaderAwareDataSource private constructor(
    private val upstream: DataSource,
) : BaseDataSource(/* isNetwork = */ false) {

    private var applied = false

    override fun open(dataSpec: DataSpec): Long {
        if (!applied) {
            applied = true
            val headers = StreamHeaders.lookup(dataSpec.key ?: dataSpec.uri.toString())
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri() = upstream.uri

    override fun close() = upstream.close()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HeaderAwareDataSource(upstreamFactory.createDataSource())
    }
}