package com.lunara.music.service.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * Paxsenix (https://lyrics.paxsenix.org): Apple Music is searched for the song,
 * candidates are scored by title / artist / duration, and the best match's
 * lyrics are fetched by Apple Music track id. Highest priority provider because
 * it verifies the song identity before accepting a result.
 */
object PaxsenixLyricsProvider : LyricsProvider {
    override val name = "Paxsenix"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    private const val PAXSENIX_LYRICS = "https://lyrics.paxsenix.org/apple-music/lyrics"

    @Volatile
    private var appleToken: String? = null

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        album: String?,
    ): String? = withContext(Dispatchers.IO) {
        val cleanTitle = LyricsUtils.cleanTitle(title)
        val cleanArtist = LyricsUtils.cleanArtist(artist)
        if (cleanTitle.isBlank()) return@withContext null

        val candidates = searchAppleMusic("$cleanTitle $cleanArtist")
            .ifEmpty { searchAppleMusic(cleanTitle) }
        if (candidates.isEmpty()) return@withContext null

        val ranked = candidates
            .map { it to score(it, title, artist, durationSeconds) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)

        for ((candidate, _) in ranked) {
            val lyrics = fetchLyrics(candidate.optString("id"))
            if (!lyrics.isNullOrBlank()) return@withContext lyrics
        }
        null
    }

    private fun appleMusicToken(): String? {
        appleToken?.let { return it }
        val page = LyricsHttp.get("https://beta.music.apple.com") ?: return null
        val jsPath = Regex("""/assets/index~[^/]+\.js""").find(page)?.value ?: return null
        val js = LyricsHttp.get("https://beta.music.apple.com$jsPath") ?: return null
        val token = Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""")
            .find(js)?.value ?: return null
        appleToken = token
        return token
    }

    private fun searchAppleMusic(term: String): List<JSONObject> {
        val token = appleMusicToken() ?: return emptyList()
        val url = "$APPLE_SEARCH?term=${URLEncoder.encode(term, "UTF-8")}" +
            "&types=songs&limit=25&l=en-US&platform=web&format[resources]=map&include[songs]=artists"
        val body = LyricsHttp.get(
            url,
            mapOf(
                "Authorization" to "Bearer $token",
                "Origin" to "https://music.apple.com",
                "Referer" to "https://music.apple.com/",
                "Accept" to "application/json"
            )
        ) ?: return emptyList()

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONObject("results")
            ?.optJSONObject("songs")
            ?.optJSONArray("data") ?: return emptyList()
        val resources = root.optJSONObject("resources")?.optJSONObject("songs")

        val out = mutableListOf<JSONObject>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            if (id.isBlank()) continue
            val attributes = resources?.optJSONObject(id)?.optJSONObject("attributes")
            out.add(
                JSONObject().apply {
                    put("id", id)
                    put("name", attributes?.optString("name", "") ?: "")
                    put("artistName", attributes?.optString("artistName", "") ?: "")
                    put("durationSeconds", (attributes?.optLong("durationInMillis", 0L) ?: 0L) / 1000)
                }
            )
        }
        return out
    }

    private fun score(candidate: JSONObject, title: String, artist: String, durationSeconds: Int): Double {
        var score = LyricsUtils.similar(title, candidate.optString("name")) * 80.0
        if (artist.isNotBlank()) {
            score += LyricsUtils.similar(artist, candidate.optString("artistName")) * 50.0
        }
        val candidateDuration = candidate.optLong("durationSeconds", 0L)
        if (durationSeconds > 0 && candidateDuration > 0) {
            val diff = abs(candidateDuration - durationSeconds)
            score += when {
                diff <= 2 -> 100.0
                diff <= 5 -> 50.0
                diff <= 10 -> 10.0
                else -> -50.0
            }
        }
        return score
    }

    private fun fetchLyrics(appleId: String?): String? {
        if (appleId.isNullOrBlank()) return null
        val body = LyricsHttp.get("$PAXSENIX_LYRICS?id=$appleId") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        json.optString("elrcMultiPerson", "").trim().takeIf { it.isNotBlank() }?.let { return it }
        json.optString("elrc", "").trim().takeIf { it.isNotBlank() }?.let { return it }

        val content = json.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val type = json.optString("type", "")
            if (type.equals("Syllable", ignoreCase = true)) {
                buildLrcFromContent(content)?.let { return it }
            }
            val plain = buildString {
                for (i in 0 until content.length()) {
                    val line = content.optJSONObject(i) ?: continue
                    val text = joinWords(line.optJSONArray("text"))
                    if (text.isNotBlank()) append(text).append('\n')
                }
            }.trim()
            if (plain.isNotBlank()) return plain
        }

        return json.optString("plain", "").trim().takeIf { it.isNotBlank() }
    }

    private fun buildLrcFromContent(content: JSONArray): String? {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val line = content.optJSONObject(i) ?: continue
            val text = joinWords(line.optJSONArray("text"))
            if (text.isBlank()) continue
            val tag = when {
                line.optBoolean("background", false) -> "{bg}"
                line.optBoolean("oppositeTurn", false) -> "{agent:v2}"
                else -> "{agent:v1}"
            }
            sb.append(formatTime(line.optLong("timestamp", 0L))).append(tag).append(text).append('\n')
        }
        return sb.toString().trimEnd().ifBlank { null }
    }

    private fun joinWords(words: JSONArray?): String {
        if (words == null) return ""
        val sb = StringBuilder()
        for (i in 0 until words.length()) {
            sb.append(words.optJSONObject(i)?.optString("text", "") ?: "")
        }
        return sb.toString().trim()
    }

    private fun formatTime(ms: Long): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val centis = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
    }
}