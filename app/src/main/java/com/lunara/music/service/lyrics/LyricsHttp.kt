package com.lunara.music.service.lyrics

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Shared OkHttp plumbing for the lyrics providers. */
internal object LyricsHttp {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder().url(url).addHeader("User-Agent", UA)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON))
            .addHeader("User-Agent", UA)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    fun postForm(url: String, form: Map<String, String>): String? = runCatching {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).addHeader("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()
}