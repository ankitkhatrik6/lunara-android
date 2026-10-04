package com.lunara.music.service.lyrics

import android.util.Log
import com.lunara.music.data.models.LyricsData
import com.lunara.music.data.models.LyricsLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Fetches synced (time-stamped) and plain lyrics from LRCLIB — the same
 * community lyrics source Blazify uses. Matching is done in two passes:
 *  1. an exact `get` lookup, and
 *  2. a scored `search` lookup (title/artist/duration similarity).
 */
object LyricsService {
    private const val TAG = "LyricsService"
    private const val BASE = "https://lrclib.net"
    private const val UA = "LunaraMusic/1.0 (https://github.com/lunara)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val LRC_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2}(?:\\.\\d{1,3})?)\\](.*)")

    private val TITLE_CLEANUP = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|remaster(ed)?|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|remaster(ed)?|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics?|visuali[sz]er).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE)
    )

    private val ARTIST_SEPARATORS = listOf(
        " & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with "
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Long = 0L
    ): LyricsData? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(title)
        val cleanArtist = cleanArtist(artist)
        if (cleanTitle.isBlank()) return@withContext null

        // 1. Exact match.
        exactMatch(cleanTitle, cleanArtist, durationSeconds)?.let { return@withContext it }

        // 2. Scored search fallback.
        searchBestMatch(cleanTitle, cleanArtist, durationSeconds)?.let { return@withContext it }

        null
    }

    private fun exactMatch(
        cleanTitle: String,
        cleanArtist: String,
        durationSeconds: Long
    ): LyricsData? {
        val url = buildString {
            append(BASE).append("/api/get?track_name=").append(encode(cleanTitle))
            if (cleanArtist.isNotBlank()) append("&artist_name=").append(encode(cleanArtist))
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
        }
        val json = getJson(url) ?: return null
        return toLyricsData(json, cleanTitle, cleanArtist)
    }

    private fun searchBestMatch(
        cleanTitle: String,
        cleanArtist: String,
        durationSeconds: Long
    ): LyricsData? {
        val queries = buildList {
            add("$BASE/api/search?track_name=${encode(cleanTitle)}&artist_name=${encode(cleanArtist)}")
            add("$BASE/api/search?q=${encode("$cleanTitle $cleanArtist")}")
        }

        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (query in queries) {
            val results = getArray(query) ?: continue
            for (i in 0 until results.length()) {
                val track = results.optJSONObject(i) ?: continue
                val matchScore = score(track, cleanTitle, cleanArtist, durationSeconds)
                if (matchScore > bestScore) {
                    bestScore = matchScore
                    best = track
                }
            }
            if (bestScore > 3.0) break // good enough, stop early
        }

        return best?.let { toLyricsData(it, cleanTitle, cleanArtist) }
    }

    private fun toLyricsData(track: JSONObject, title: String, artist: String): LyricsData? {
        val synced = track.optString("syncedLyrics", "").trim()
        val plain = track.optString("plainLyrics", "").trim()

        if (synced.isNotBlank()) {
            val lines = parseSyncedLyrics(synced)
            if (lines.isNotEmpty()) {
                return LyricsData(
                    songTitle = title,
                    artist = artist,
                    plainLyrics = plain.ifBlank { null },
                    lines = lines,
                    isSynced = true
                )
            }
        }
        if (plain.isNotBlank()) {
            return LyricsData(
                songTitle = title,
                artist = artist,
                plainLyrics = plain,
                isSynced = false
            )
        }
        return null
    }

    private fun score(
        track: JSONObject,
        cleanTitle: String,
        cleanArtist: String,
        durationSeconds: Long
    ): Double {
        var score = 0.0
        val trackName = track.optString("trackName", "")
        val artistName = track.optString("artistName", "")

        score += similar(cleanTitle, trackName) * 2.0
        if (cleanArtist.isNotBlank()) score += similar(cleanArtist, artistName) * 1.5

        if (track.optString("syncedLyrics", "").isNotBlank()) score += 1.0
        if (track.optString("plainLyrics", "").isNotBlank()) score += 0.3

        val trackDuration = track.optDouble("duration", 0.0)
        if (durationSeconds > 0 && trackDuration > 0) {
            val diff = abs(trackDuration - durationSeconds)
            score += when {
                diff <= 3 -> 1.0
                diff <= 8 -> 0.6
                diff <= 20 -> 0.2
                else -> -0.8
            }
        }
        return score
    }

    private fun similar(a: String, b: String): Double {
        val s1 = a.trim().lowercase()
        val s2 = b.trim().lowercase()
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        if (s1.contains(s2) || s2.contains(s1)) return 0.8
        val maxLen = maxOf(s1.length, s2.length)
        return (1.0 - levenshtein(s1, s2).toDouble() / maxLen).coerceAtLeast(0.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun getJson(url: String): JSONObject? {
        val body = httpGet(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun getArray(url: String): JSONArray? {
        val body = httpGet(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", UA)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.onFailure { Log.w(TAG, "Lyrics request failed: ${it.message}") }.getOrNull()
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in TITLE_CLEANUP) cleaned = cleaned.replace(pattern, "")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in ARTIST_SEPARATORS) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private fun parseSyncedLyrics(lrc: String): List<LyricsLine> {
        val list = mutableListOf<LyricsLine>()
        for (line in lrc.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val matcher = LRC_PATTERN.matcher(trimmed)
            if (matcher.matches()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val secParts = (matcher.group(2) ?: "0").split(".")
                val sec = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                list.add(LyricsLine((min * 60 + sec) * 1000 + ms, matcher.group(3)?.trim() ?: ""))
            }
        }
        return list.sortedBy { it.timeMs }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
