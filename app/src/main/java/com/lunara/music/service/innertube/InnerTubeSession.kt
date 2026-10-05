package com.lunara.music.service.innertube

import android.util.Log
import com.lunara.extractor.ClientRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shared InnerTube identity state.
 *
 * Blazify's InnerTube client treats a blank identity as worse than no identity:
 * a missing visitor header lets YouTube mint one, while an empty one is
 * refused (which surfaces as "Video unavailable" / LOGIN_REQUIRED for every
 * play). This helper applies the same rule and lets callers learn the visitor
 * id shipped back inside player/browse/search responses.
 */
object InnerTubeSession {
    private const val TAG = "InnerTubeSession"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Makes sure a usable anonymous visitor id is available. Any stored value
     * that is actually blank is discarded so requests never send an empty
     * identity header.
     */
    suspend fun refreshVisitorData(scope: Any? = null): String? = withContext(Dispatchers.IO) {
        cleanseVisitorData()
        val current = YouTubeSession.visitorData
        if (!current.isNullOrBlank()) return@withContext current

        for (host in listOf(ClientRegistry.ORIGIN_YOUTUBE_MUSIC, ClientRegistry.ORIGIN_YOUTUBE)) {
            val found = runCatching {
                val request = Request.Builder()
                    .url("$host/")
                    .addHeader("User-Agent", ClientRegistry.USER_AGENT_WEB)
                    .build()
                http.newCall(request).execute().use { resp ->
                    extractVisitorData(resp.body?.string().orEmpty())
                }
            }.onFailure { Log.w(TAG, "visitorData fetch failed for $host: ${it.message}") }
                .getOrNull()

            if (!found.isNullOrBlank()) {
                YouTubeSession.rememberVisitorData(found)
                return@withContext found
            }
        }
        null
    }

    /** Harvest the visitor id returned inside any InnerTube JSON response. */
    fun rememberFromResponse(responseBody: org.json.JSONObject) {
        extractVisitorData(responseBody.toString())?.let { YouTubeSession.rememberVisitorData(it) }
    }

    private fun cleanseVisitorData() {
        val current = YouTubeSession.visitorData
        if (current != null && (current.isBlank() || current == "null" || current == "undefined")) {
            YouTubeSession.rememberVisitorData(null)
        }
    }

    private fun extractVisitorData(text: String): String? {
        if (text.isBlank()) return null
        val marker = "\"visitorData\":\""
        val start = text.indexOf(marker)
        if (start == -1) return null
        val from = start + marker.length
        val end = text.indexOf('"', from)
        return text.substring(from, end).takeIf { end > from && it.isNotBlank() }
    }
}