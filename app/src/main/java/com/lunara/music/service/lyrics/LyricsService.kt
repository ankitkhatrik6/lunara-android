package com.lunara.music.service.lyrics

import com.lunara.music.data.models.LyricsData

/**
 * Facade over [LyricsRepository]. Converts the provider LRC into the UI model.
 *
 * Providers are consulted in priority order:
 * Paxsenix, LrcLib, BetterLyrics, KuGou, LyricsPlus.
 */
object LyricsService {

    /** Call once at application start (enables the WebView based Better Lyrics provider). */
    fun init(context: android.content.Context) {
        BetterLyricsLyricsProvider.init(context)
    }

    val providerNames: List<String> get() = LyricsRepository.providerNames

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Long = 0L,
        videoId: String = "",
    ): LyricsData? {
        val result = LyricsRepository.getLyrics(
            id = videoId,
            title = title,
            artist = artist,
            durationSeconds = durationSeconds
        ) ?: return null

        val lrc = result.first
        val lines = LyricsUtils.parseLrc(lrc)
        if (lines.isNotEmpty()) {
            val plainFallback = lrc.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("<") && !it.startsWith("{") }
                .joinToString("\n")
                .ifBlank { null }
            return LyricsData(
                songTitle = title,
                artist = artist,
                plainLyrics = plainFallback,
                lines = lines,
                isSynced = true
            )
        }

        val plain = lrc.lines()
            .map {
                it.trim()
                    .replace(Regex("""^\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]"""), "")
                    .replace(Regex("""<[^>\n]*>"""), "")
                    .replace(Regex("""\{(?:agent:[^}]+|bg)\}"""), "")
                    .trim()
            }
            .filter { it.isNotBlank() && it != "[null]" && it != "null" }
            .joinToString("\n")
            .ifBlank { null }
            ?: return null

        return LyricsData(
            songTitle = title,
            artist = artist,
            plainLyrics = plain,
            lines = emptyList(),
            isSynced = false
        )
    }
}