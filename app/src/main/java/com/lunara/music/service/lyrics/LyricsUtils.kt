package com.lunara.music.service.lyrics

import com.lunara.music.data.models.LyricsLine
import java.util.regex.Pattern

/** Shared LRC parsing and title / artist normalisation used by all providers. */
object LyricsUtils {
    private val LRC_LINE = Pattern.compile("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
    private val AGENT_TAG = Regex("""\{(?:agent:[^}]+|bg)\}""")
    private val WORD_BLOCK = Regex("""^<.+>$""")

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

    fun parseLrc(text: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || WORD_BLOCK.matches(line)) continue
            val matcher = LRC_LINE.matcher(line)
            if (!matcher.matches()) continue
            val min = matcher.group(1)?.toLongOrNull() ?: 0L
            val sec = matcher.group(2)?.toLongOrNull() ?: 0L
            val frac = matcher.group(3)?.let { it.padEnd(3, '0').take(3).toLongOrNull() } ?: 0L
            val content = AGENT_TAG.replace(matcher.group(4) ?: "", "").trim()
            lines.add(LyricsLine((min * 60 + sec) * 1000 + frac, content))
        }
        return lines.sortedBy { it.timeMs }
    }

    fun isSynced(text: String): Boolean = parseLrc(text).isNotEmpty()

    fun lastTimestampMs(text: String): Long = parseLrc(text).maxOfOrNull { it.timeMs } ?: 0L

    fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in TITLE_CLEANUP) cleaned = cleaned.replace(pattern, "")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in ARTIST_SEPARATORS) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    /** 0.0 (nothing alike) .. 1.0 (identical), tolerant of containment. */
    fun similar(a: String, b: String): Double {
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
}