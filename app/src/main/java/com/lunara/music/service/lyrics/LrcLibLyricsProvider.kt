package com.lunara.music.service.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/** LRCLIB (https://lrclib.net): exact match first, then a scored search. */
object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"
    private const val BASE = "https://lrclib.net"

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

        exact(cleanTitle, cleanArtist, durationSeconds)?.let { return@withContext it }
        search(cleanTitle, cleanArtist, durationSeconds)
    }

    private fun exact(title: String, artist: String, duration: Int): String? {
        val url = buildString {
            append(BASE).append("/api/get?track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
            if (duration > 0) append("&duration=").append(duration)
        }
        val json = LyricsHttp.get(url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return pick(json)
    }

    private fun search(title: String, artist: String, duration: Int): String? {
        val url = "$BASE/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val array = LyricsHttp.get(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until array.length()) {
            val track = array.optJSONObject(i) ?: continue
            val score = score(track, title, artist, duration)
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best?.let { pick(it) }
    }

    private fun pick(track: JSONObject): String? {
        track.optString("syncedLyrics", "").trim().takeIf { it.isNotBlank() }?.let { return it }
        return track.optString("plainLyrics", "").trim().takeIf { it.isNotBlank() }
    }

    private fun score(track: JSONObject, title: String, artist: String, duration: Int): Double {
        var score = LyricsUtils.similar(title, track.optString("trackName", "")) * 2.0
        if (artist.isNotBlank()) {
            score += LyricsUtils.similar(artist, track.optString("artistName", "")) * 1.5
        }
        if (track.optString("syncedLyrics", "").isNotBlank()) score += 1.0
        val trackDuration = track.optDouble("duration", 0.0)
        if (duration > 0 && trackDuration > 0) {
            val diff = abs(trackDuration - duration)
            score += when {
                diff <= 3 -> 1.0
                diff <= 8 -> 0.6
                diff <= 20 -> 0.2
                else -> -0.8
            }
        }
        return score
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}