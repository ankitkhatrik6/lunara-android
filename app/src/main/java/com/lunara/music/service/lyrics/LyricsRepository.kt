package com.lunara.music.service.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the lyrics providers in the requested priority order and returns the
 * first plausible result.
 *
 * Priority: Paxsenix, LrcLib, BetterLyrics, KuGou, LyricsPlus.
 */
object LyricsRepository {
    private const val TAG = "LyricsRepository"
    private const val PER_PROVIDER_TIMEOUT_MS = 12_000L
    private const val MAX_CACHE_ENTRIES = 32

    private val providers: List<LyricsProvider> = listOf(
        PaxsenixLyricsProvider,
        LrcLibLyricsProvider,
        BetterLyricsLyricsProvider,
        KuGouLyricsProvider,
        LyricsPlusLyricsProvider,
    )

    val providerNames: List<String> get() = providers.map { it.name }

    private data class Entry(val lyrics: String, val provider: String)

    private val cache = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > MAX_CACHE_ENTRIES
    }

    /**
     * Returns the lyrics text and the provider that supplied it, or null when no
     * provider had a usable result.
     */
    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Long,
        album: String? = null,
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null

        if (id.isNotBlank()) {
            synchronized(cache) { cache[id] }?.let { return@withContext it.lyrics to it.provider }
        }

        val durationInt = durationSeconds.toInt()

        for (provider in providers) {
            val lyrics = runCatching {
                withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                    provider.getLyrics(id, title, artist, durationInt, album)
                }
            }.onFailure { Log.w(TAG, "${provider.name} failed: ${it.message}") }.getOrNull()

            if (lyrics.isNullOrBlank()) continue
            if (!isPlausible(lyrics, durationInt)) {
                Log.d(TAG, "${provider.name} result rejected as implausible")
                continue
            }

            if (id.isNotBlank()) synchronized(cache) { cache[id] = Entry(lyrics, provider.name) }
            Log.d(TAG, "Lyrics for \"$title\" from ${provider.name}")
            return@withContext lyrics to provider.name
        }

        null
    }

    /**
     * Sanity check: for synced lyrics the last timestamp must fall inside a
     * sensible window of the track length. Plain lyrics cannot be time-verified
     * and are accepted as-is.
     */
    private fun isPlausible(lyrics: String, durationSeconds: Int): Boolean {
        if (durationSeconds <= 0) return true
        val entries = LyricsUtils.parseLrc(lyrics)
        if (entries.isEmpty()) return true
        val lastMs = entries.maxOf { it.timeMs }
        val durationMs = durationSeconds * 1000L
        return lastMs >= (durationMs * 0.4).toLong() && lastMs <= durationMs + 15_000L
    }
}