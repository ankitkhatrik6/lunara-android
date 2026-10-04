package com.lunara.music.service.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * LyricsPlus: a community aggregator served from several mirrors. Returns
 * line-synced or word-synced lyrics, converted to extended LRC.
 */
object LyricsPlusLyricsProvider : LyricsProvider {
    override val name = "LyricsPlus"

    private val baseUrls = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus-seven.vercel.app"
    )

    @Volatile
    private var lastWorking: String? = null

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        album: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val servers = lastWorking
            ?.let { last -> listOf(last) + baseUrls.filter { it != last } }
            ?: baseUrls

        for (base in servers) {
            val url = buildString {
                append(base).append("/v2/lyrics/get?title=").append(enc(title))
                append("&artist=").append(enc(artist))
                if (durationSeconds > 0) append("&duration=").append(durationSeconds)
                if (!album.isNullOrBlank()) append("&album=").append(enc(album))
            }
            val body = LyricsHttp.get(url) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val lyrics = json.optJSONArray("lyrics") ?: continue
            if (lyrics.length() == 0) continue
            val isWord = json.optString("type", "").equals("Word", ignoreCase = true)
            val lrc = convert(lyrics, isWord)
            if (!lrc.isNullOrBlank()) {
                lastWorking = base
                return@withContext lrc
            }
        }
        null
    }

    private fun convert(lyrics: JSONArray, isWord: Boolean): String? {
        val sb = StringBuilder()
        for (i in 0 until lyrics.length()) {
            val line = lyrics.optJSONObject(i) ?: continue

            val mainWords = mutableListOf<JSONObject>()
            line.optJSONArray("syllabus")?.let { syllabus ->
                for (j in 0 until syllabus.length()) {
                    val word = syllabus.optJSONObject(j) ?: continue
                    if (!word.optBoolean("isBackground", false)) mainWords.add(word)
                }
            }

            val lineText = if (isWord && mainWords.isNotEmpty()) {
                mainWords.joinToString("") { it.optString("text", "") }.trim()
            } else {
                line.optString("text", "").trim()
            }
            if (lineText.isBlank()) continue

            sb.append(formatTime(line.optLong("time", 0L))).append(lineText).append('\n')

            if (isWord && mainWords.isNotEmpty()) {
                val valid = mainWords.filter { it.optString("text", "").isNotBlank() }
                if (valid.isNotEmpty()) {
                    sb.append('<')
                    valid.forEachIndexed { index, word ->
                        val start = word.optLong("time", 0L) / 1000.0
                        val end = (word.optLong("time", 0L) + word.optLong("duration", 0L)) / 1000.0
                        sb.append(word.optString("text", "").trim())
                            .append(':').append(start).append(':').append(end)
                        if (index < valid.lastIndex) sb.append('|')
                    }
                    sb.append(">\n")
                }
            }
        }
        return sb.toString().trimEnd().ifBlank { null }
    }

    private fun formatTime(ms: Long): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val centis = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}