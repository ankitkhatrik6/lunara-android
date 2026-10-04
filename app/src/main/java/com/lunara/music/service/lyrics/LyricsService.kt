package com.lunara.music.service.lyrics

import android.content.Context
import com.lunara.music.data.models.LyricsData

/**
 * Facade over [LyricsRepository]. Converts the provider LRC into the UI model.
 *
 * Providers are consulted in priority order:
 * Paxsenix, LrcLib, BetterLyrics, KuGou, LyricsPlus.
 */
object LyricsService {

    /** Call once at application start (enables the WebView based Better Lyrics provider). */
    fun init(context: Context) {
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
        return LyricsData(
            songTitle = title,
            artist = artist,
            plainLyrics = if (lines.isEmpty()) lrc else null,
            lines = lines,
            isSynced = lines.isNotEmpty()
        )
    }
}