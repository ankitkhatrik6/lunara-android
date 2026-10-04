package com.lunara.music.service.lyrics

/**
 * A lyrics source. Providers are consulted in priority order by
 * [LyricsRepository] and return an LRC string (optionally extended with
 * word-level blocks) or null when they have nothing for the song.
 */
interface LyricsProvider {
    val name: String

    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        album: String? = null,
    ): String?
}