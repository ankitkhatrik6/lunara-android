package com.lunara.music.service.lyrics

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/** KuGou lyrics (https://lyrics.kugou.com), matched by title / artist / duration. */
object KuGouLyricsProvider : LyricsProvider {
    override val name = "KuGou"
    private const val DURATION_TOLERANCE = 8

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        album: String?,
    ): String? = withContext(Dispatchers.IO) {
        val keyword = "${LyricsUtils.cleanTitle(title)} - ${LyricsUtils.cleanArtist(artist)}"
        val searchUrl = buildString {
            append("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc")
            if (durationSeconds > 0) append("&duration=").append(durationSeconds * 1000)
            append("&keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        val json = LyricsHttp.get(searchUrl)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return@withContext null
        val candidates = json.optJSONArray("candidates") ?: return@withContext null

        var fallback: String? = null
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val candidateDuration = candidate.optLong("duration", 0L) / 1000
            val content = download(candidate.optLong("id"), candidate.optString("accesskey")) ?: continue
            if (content.isBlank()) continue

            if (durationSeconds <= 0 ||
                abs(candidateDuration - durationSeconds) <= DURATION_TOLERANCE
            ) {
                return@withContext content
            }
            if (fallback == null) fallback = content
        }
        fallback
    }

    private fun download(id: Long, accessKey: String): String? {
        val url = "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1" +
            "&id=$id&accesskey=" + URLEncoder.encode(accessKey, "UTF-8")
        val json = LyricsHttp.get(url)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val encoded = json.optString("content", "")
        if (encoded.isBlank()) return null
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }
}