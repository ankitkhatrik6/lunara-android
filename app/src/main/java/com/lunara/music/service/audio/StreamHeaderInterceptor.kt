package com.lunara.music.service.audio

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping a stream URL to the HTTP headers it must be requested with.
 *
 * A googlevideo URL is only served to the same InnerTube identity that minted
 * it, so the headers that produced the URL have to travel with every media
 * request. [LunaraPlayerManager] registers them the moment a stream resolves and
 * [StreamHeaderInterceptor] applies them to the request.
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
