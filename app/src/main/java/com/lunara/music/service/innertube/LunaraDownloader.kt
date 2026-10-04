package com.lunara.music.service.innertube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [Downloader] implementation backed by OkHttp, used by BlazifyExtractor to fetch
 * YouTube player/watch pages while resolving a playable stream.
 */
class LunaraDownloader(private val client: OkHttpClient) : Downloader() {

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val call = client.newCall(buildOkHttpRequest(request))
        val response = call.execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha challenge", request.url())
        }
        return toExtractorResponse(response)
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun executeAsync(request: Request, callback: Downloader.AsyncCallback): CancellableCall {
        val call = client.newCall(buildOkHttpRequest(request))
        val cancellableCall = CancellableCall(call)

        executor.execute {
            try {
                val response = call.execute()
                if (response.code == 429) {
                    response.close()
                    throw ReCaptchaException("reCaptcha challenge", request.url())
                }
                callback.onSuccess(toExtractorResponse(response))
            } catch (e: Exception) {
                callback.onError(e)
            } finally {
                cancellableCall.setFinished()
            }
        }
        return cancellableCall
    }

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        return builder.build()
    }

    private fun toExtractorResponse(response: okhttp3.Response): Response {
        val bodyBytes: ByteArray = response.body?.bytes() ?: ByteArray(0)
        val bodyText = if (bodyBytes.isEmpty()) "" else String(bodyBytes, Charsets.UTF_8)
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            bodyText,
            bodyBytes,
            response.request.url.toString()
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
