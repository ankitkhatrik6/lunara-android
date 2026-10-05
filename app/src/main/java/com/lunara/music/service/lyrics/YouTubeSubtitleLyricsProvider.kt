package com.lunara.music.service.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * YouTube timed captions (Blazify YouTubeSubtitleLyricsProvider equivalent).
 * Last-resort provider: fetches the video's caption track list via the
 * InnerTube player response and converts the first usable track to LRC.
 * This is what covers Bollywood / regional tracks the text providers miss.
 */
object YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val name = "YouTube"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        album: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val player = runCatching {
            com.lunara.music.service.innertube.InnerTubePlayer.fetchPlayerResponse(
                com.lunara.music.service.innertube.InnerTubeClients.STREAM_CLIENTS.first(),
                id
            )
        }.getOrNull() ?: return@withContext null
        val tracks = player.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return@withContext null
        // Prefer English/auto, else first track.
        var baseUrl: String? = null
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val code = t.optString("languageCode", "")
            val url = t.optString("baseUrl", "")
            if (url.isBlank()) continue
            if (baseUrl == null) baseUrl = url
            if (code.startsWith("en")) { baseUrl = url; break }
        }
        val xml = baseUrl?.let { LyricsHttp.get("$it&fmt=vtt") ?: LyricsHttp.get(it) }
            ?: return@withContext null
        vttToLrc(xml)?.takeIf { LyricsUtils.isSynced(it) }
    }

    private fun vttToLrc(body: String): String? {
        // Handles both VTT (00:00.000 --> 00:01.200) and TTML (<p begin= end=>).
        val out = StringBuilder()
        val vtt = Regex("""(\d{1,2}):(\d{2})[.:](\d{3})\s*-->\s*(\d{1,2}):(\d{2})[.:](\d{3})\s*\n([\s\S]*?)(?=\n\n|\n\d{1,2}:|$)""")
        var matched = false
        for (m in vtt.findAll(body)) {
            val text = m.groupValues[7].lines()
                .map { it.trim().removePrefix("-->").trim() }
                .filter { it.isNotBlank() && !it.startsWith("NOTE") && !it.startsWith("WEBVTT") }
                .joinToString(" ") { it.replace(Regex("<[^>]+>"), "").trim() }
                .trim()
            if (text.isBlank()) continue
            matched = true
            out.append("[${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}.${m.groupValues[3]}]$text\n")
        }
        if (matched) return out.toString().trim().ifBlank { null }
        val ttml = Regex("""<p[^>]*begin="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        for (m in ttml.findAll(body)) {
            val ms = parseTtmlTime(m.groupValues[1]) ?: continue
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
            if (text.isBlank()) continue
            matched = true
            val min = ms / 60000; val sec = (ms % 60000) / 1000; val cs = (ms % 1000) / 10
            out.append("[%02d:%02d.%02d]%s\n".format(min, sec, cs, text))
        }
        return out.toString().trim().ifBlank { null }.takeIf { matched }
    }

    private fun parseTtmlTime(t: String): Long? = runCatching {
        val clean = t.trim()
        if (clean.endsWith("s")) return (clean.dropLast(1).toDouble() * 1000).toLong()
        val parts = clean.split(":")
        when (parts.size) {
            3 -> {
                val secParts = parts[2].split(".", ",")
                (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + secParts[0].toLong()) * 1000 +
                    secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull()?.times(1)!!.let {
                        if (secParts.getOrNull(1)?.length == 2) it * 10 else it
                    }
            }
            2 -> (parts[0].toLong() * 60 + parts[1].substringBefore(".").toLong()) * 1000
            else -> null
        }
    }.getOrNull()
}
