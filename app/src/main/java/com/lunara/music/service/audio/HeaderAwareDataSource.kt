package com.lunara.music.service.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping a stream URL to the HTTP headers it must be requested with.
 *
 * A googlevideo URL is only served to the same InnerTube identity that minted
 * it, so the headers that produced the URL have to travel with every media
 * request. [LunaraPlayerManager] registers them the moment a stream resolves and
 * [HeaderAwareDataSource] looks them up per request.
 *
 * The map is bounded so a long listening session cannot grow it without limit.
 */
object StreamHeaders {
    private const val MAX_ENTRIES = 16

    private val headersByUrl = ConcurrentHashMap<String, Map<String, String>>()

    /** Binds [url] to [headers] for as long as the current item is playing. */
    fun register(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        while (headersByUrl.size >= MAX_ENTRIES) {
            val oldest = headersByUrl.keys.firstOrNull() ?: break
            headersByUrl.remove(oldest)
        }
        headersByUrl[url] = headers
    }

    fun lookup(url: String): Map<String, String> = headersByUrl[url].orEmpty()

    fun clear() = headersByUrl.clear()
}

/**
 * An HTTP data source that decorates an upstream one so every request carries
 * the headers registered for the URI it is about to open.
 *
 * Media3 hands the data source exactly the URI that was set on the `MediaItem`,
 * which is the URL the resolver produced, so the registry lookup is exact.
 */
class HeaderAwareDataSource(
    private val upstream: HttpDataSource,
) : HttpDataSource.Base(/* isNetwork = */ true) {

    override fun open(dataSpec: DataSpec): Long {
        val key = dataSpec.key ?: dataSpec.uri?.toString().orEmpty()
        StreamHeaders.lookup(key).forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun close() = upstream.close()

    class Factory(private val upstreamFactory: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HeaderAwareDataSource(upstreamFactory.createDataSource())
    }
}
