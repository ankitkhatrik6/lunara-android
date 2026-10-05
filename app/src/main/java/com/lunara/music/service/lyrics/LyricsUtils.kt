package com.lunara.music.service.lyrics

import com.lunara.music.data.models.LyricsLine
import java.util.regex.Pattern

/** Shared LRC parsing and title / artist normalisation used by all providers. */
object LyricsUtils {
    private val LRC_LINE = Pattern.compile("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
    private val ALL_TIMESTAMPS = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val AGENT_TAG = Regex("""\{(?:agent:[^}]+|bg)\}""")
    private val WORD_BLOCK = Regex("""^<.+>$""")
    private val INLINE_WORD_BLOCK = Regex("""<[^>\n]*>""")
    // Blazify Paxsenix formats:
    //   [00:00.000]v1: <00:00.000>I <00:00.154>promise...
    //   [bg: <02:18.078>Yeah<02:19.341>]
    //   {agent:v1} / {bg} inline markers
    private val PAXSENIX_AGENT_LINE = Regex("""^\[(\d{1,2}):(\d{2})\.(\d{2,3})\](v\d+):\s*(.*)$""")
    private val PAXSENIX_BG_LINE = Regex("""^\[bg:\s*(.*)\]$""")
    private val PAXSENIX_WORD = Regex("""<(\d{1,2}):(\d{2})\.(\d{2,3})>([^<]*)""")
    private val OFFSET_TAG = Regex("""(?m)^\s*\[offset:\s*([+-]?\d+)\s*\]""")

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
        if (text.isBlank()) return lines
        val offsetMs = OFFSET_TAG.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("{") && !line.startsWith("{agent") && !line.startsWith("{bg")) continue
            if (WORD_BLOCK.matches(line)) continue
            // [offset:+500] metadata line — never a lyric.
            if (line.startsWith("[offset:", ignoreCase = true)) continue
            // [bg: <02:18.078>Yeah<02:19.341>] background line.
            val bg = PAXSENIX_BG_LINE.find(line)
            if (bg != null) {
                val content = cleanContent(bg.groupValues[1])
                if (content.isNotBlank()) {
                    val ms = firstWordMs(bg.groupValues[1]) ?: 0L
                    lines.add(LyricsLine(ms + offsetMs, content))
                }
                continue
            }
            // [00:00.000]v1: <00:00.000>I <00:00.154>promise...
            val agent = PAXSENIX_AGENT_LINE.find(line)
            if (agent != null) {
                val min = agent.groupValues[1].toLongOrNull() ?: 0L
                val sec = agent.groupValues[2].toLongOrNull() ?: 0L
                val frac = agent.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val content = cleanContent(agent.groupValues[5])
                if (content.isNotBlank() && content != "[null]" && content != "null") {
                    lines.add(LyricsLine((min * 60 + sec) * 1000 + frac + offsetMs, content))
                }
                continue
            }
            // Standard LRC — may carry several stamps for one line. The stamp
            // may also sit in the "flex" columns area with no trailing "]"
            // split point, so fall back to stripping leading stamps.
            val stamps = ALL_TIMESTAMPS.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val afterLast = line.substringAfterLast("]")
            val content = cleanContent(
                if (afterLast.isNotBlank() && !afterLast.contains("[")) afterLast
                else ALL_TIMESTAMPS.replace(line, "").trim()
            )
            if (content.isBlank() || content == "[null]" || content == "null") continue
            for (stamp in stamps) {
                val min = stamp.groupValues[1].toLongOrNull() ?: 0L
                val sec = stamp.groupValues[2].toLongOrNull() ?: 0L
                val frac = stamp.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                lines.add(LyricsLine((min * 60 + sec) * 1000 + frac + offsetMs, content))
            }
        }
        return lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
    }

    /** Strips Paxsenix word timings, agent/bg markers and credit junk. */
    private fun cleanContent(raw: String): String {
        var text = AGENT_TAG.replace(raw, "")
        // <00:00.154> word timings -> keep the word, drop the stamp.
        text = PAXSENIX_WORD.replace(text) { it.groupValues[4] }
        text = INLINE_WORD_BLOCK.replace(text, "")
        // word:start:end pipe segments (LyricsPlus word-sync).
        if (text.contains("|")) {
            text = text.split("|")
                .map { it.substringBefore(":").trim() }
                .filter { it.isNotBlank() && !it.startsWith("<") && !it.contains("]") && !it.contains("{") }
                .joinToString(" ")
        }
        // v1: / v2: prefix leftovers.
        text = text.replace(Regex("""^\s*v\d+:\s*"""), "")
        return text.trim()
    }

    private fun firstWordMs(raw: String): Long? {
        val m = PAXSENIX_WORD.find(raw) ?: return null
        val min = m.groupValues[1].toLongOrNull() ?: 0L
        val sec = m.groupValues[2].toLongOrNull() ?: 0L
        val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return (min * 60 + sec) * 1000 + frac
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