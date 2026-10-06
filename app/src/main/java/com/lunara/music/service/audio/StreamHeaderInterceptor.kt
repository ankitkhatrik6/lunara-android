package com.lunara.music.service.audio

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Registry mapping a stream URL to the HTTP headers it must be requested with.
 *
 * A googlevideo URL is only served to the same InnerTube identity that minted
 * it, so the headers that produced the URL have to travel with every media
 * request. [LunaraPlayerManager] registers them the moment a stream resolves,
 * [StreamResolvingDataSource] re-registers them at every chunk open, and
 * [StreamHeaderInterceptor] applies them to the request.
 *
 * Two properties matter for playback:
 *
 *  - **Access-ordered LRU.** The old map evicted an arbitrary `ConcurrentHashMap`
 *    key, which in a long session could drop the identity of the URL currently
 *    playing — every later range request then went out anonymously and was
 *    refused. An LRU can only ever evict what has not been used longest.
 *  - **Host-level fallback.** The exact URL string can differ from the request
 *    OkHttp ends up making (normalisation, a CDN rewrite). The identity headers
 *    are per minting client, and googlevideo hosts carry no per-URL variance, so
 *    a request that does not match exactly still finds its headers by host.
 */
object StreamHeaders {
    private const val MAX_ENTRIES = 16

    private val headersByUrl =
        object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Map<String, String>>,
            ): Boolean = size > MAX_ENTRIES
        }

    /** Binds [url] to [headers] for as long as the stream is played. */
    fun register(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        synchronized(headersByUrl) { headersByUrl[url] = headers }
    }

    fun lookup(url: String): Map<String, String> {
        synchronized(headersByUrl) {
            headersByUrl[url]?.let { return it }

            val host = hostOf(url) ?: return emptyMap()
            // Access order: the first entry on the same host is the most recently
            // used one, which is the identity the live request most likely wants.
            for ((registeredUrl, headers) in headersByUrl) {
                if (hostOf(registeredUrl) == host) return headers
            }
        }
        return emptyMap()
    }

    fun clear() {
        synchronized(headersByUrl) { headersByUrl.clear() }
    }

    /** The host part of an http(s) URL, or null when the string is not one. */
    internal fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host
}

/**
 * Applies the identity headers registered for a stream to every request the
 * player makes for it.
 *
 * This runs inside the OkHttp client that Media3 already uses for media, so the
 * headers are attached to the very same connection pool and redirect chain as
 * the rest of the request. Requests for URLs that were never registered (local
 * files, artwork, the InnerTube API) pass through untouched.
 */
class StreamHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = StreamHeaders.lookup(request.url.toString())
        if (headers.isEmpty()) return chain.proceed(request)

        val builder = request.newBuilder()
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
        }
        return chain.proceed(builder.build())
    }
}
