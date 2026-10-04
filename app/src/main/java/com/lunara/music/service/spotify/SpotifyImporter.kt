package com.lunara.music.service.spotify

import android.util.Log
import com.lunara.music.data.models.Song
import com.lunara.music.service.innertube.InnerTubeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SpotifyImportProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val matchedCount: Int = 0,
    val unmatchedCount: Int = 0,
    val currentTrackTitle: String = "",
    val playlistName: String = "",
    val isComplete: Boolean = false,
    val matchedSongs: List<Song> = emptyList(),
    val unmatchedTracks: List<String> = emptyList(),
    val errorMessage: String? = null
)

object SpotifyImporter {
    private const val TAG = "SpotifyImporter"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val SPOTIFY_URL_PATTERN = Pattern.compile("playlist[/:]([a-zA-Z0-9]+)")

    fun extractPlaylistId(urlOrUri: String): String? {
        val matcher = SPOTIFY_URL_PATTERN.matcher(urlOrUri)
        return if (matcher.find()) matcher.group(1) else null
    }

    private data class RawTrack(
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    fun importSpotifyPlaylist(urlOrUri: String): Flow<SpotifyImportProgress> = flow {
        val playlistId = extractPlaylistId(urlOrUri)
        if (playlistId == null) {
            emit(SpotifyImportProgress(stage = "Error", errorMessage = "Invalid Spotify playlist link or ID"))
            return@flow
        }

        emit(SpotifyImportProgress(stage = "Connecting to Spotify...", current = 0, total = 0))

        val tracks = mutableListOf<RawTrack>()
        var playlistTitle = "Imported Spotify Playlist"

        try {
            val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
            val req = Request.Builder()
                .url(embedUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(SpotifyImportProgress(stage = "Error", errorMessage = "Could not fetch Spotify playlist (HTTP ${resp.code})"))
                    return@flow
                }
                val html = resp.body?.string() ?: ""

                // Extract __NEXT_DATA__
                val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                val start = html.indexOf(marker)
                if (start != -1) {
                    val end = html.indexOf("</script>", start)
                    if (end != -1) {
                        val jsonStr = html.substring(start + marker.length, end)
                        val root = JSONObject(jsonStr)
                        val entity = root.optJSONObject("props")
                            ?.optJSONObject("pageProps")
                            ?.optJSONObject("state")
                            ?.optJSONObject("data")
                            ?.optJSONObject("entity")

                        playlistTitle = entity?.optString("title", playlistTitle) ?: playlistTitle
                        val trackList = entity?.optJSONArray("trackList")

                        if (trackList != null) {
                            for (i in 0 until trackList.length()) {
                                val tObj = trackList.optJSONObject(i) ?: continue
                                val title = tObj.optString("title", tObj.optString("name", ""))
                                val artist = tObj.optString("subtitle", "")
                                val durationMs = tObj.optLong("duration", 0L)
                                if (title.isNotBlank()) {
                                    tracks.add(RawTrack(title = title, artist = artist, durationMs = durationMs))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching embed: ${e.message}", e)
            emit(SpotifyImportProgress(stage = "Error", errorMessage = "Could not connect to Spotify: ${e.localizedMessage}"))
            return@flow
        }

        if (tracks.isEmpty()) {
            emit(SpotifyImportProgress(stage = "Error", errorMessage = "No tracks found in playlist. Make sure the playlist is public."))
            return@flow
        }

        emit(
            SpotifyImportProgress(
                stage = "Found ${tracks.size} tracks. Matching with YouTube Music...",
                current = 0,
                total = tracks.size,
                playlistName = playlistTitle
            )
        )

        val matchedSongs = mutableListOf<Song>()
        val unmatchedTracks = mutableListOf<String>()

        for (index in tracks.indices) {
            val raw = tracks[index]
            emit(
                SpotifyImportProgress(
                    stage = "Matching track ${index + 1} of ${tracks.size}",
                    current = index + 1,
                    total = tracks.size,
                    matchedCount = matchedSongs.size,
                    unmatchedCount = unmatchedTracks.size,
                    currentTrackTitle = "${raw.title} - ${raw.artist}",
                    playlistName = playlistTitle
                )
            )

            val query = "${raw.title} ${raw.artist}".trim()
            val searchResult = InnerTubeService.search(query)
            val matchedSong = findBestMatch(raw, searchResult.songs)

            if (matchedSong != null) {
                matchedSongs.add(matchedSong)
            } else {
                unmatchedTracks.add("${raw.title} - ${raw.artist}")
            }
        }

        emit(
            SpotifyImportProgress(
                stage = "Import Complete",
                current = tracks.size,
                total = tracks.size,
                matchedCount = matchedSongs.size,
                unmatchedCount = unmatchedTracks.size,
                playlistName = playlistTitle,
                isComplete = true,
                matchedSongs = matchedSongs,
                unmatchedTracks = unmatchedTracks
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun findBestMatch(raw: RawTrack, candidates: List<Song>): Song? {
        if (candidates.isEmpty()) return null

        val normTitle = normalize(raw.title)
        val normArtist = normalize(raw.artist)
        val targetDurationSec = raw.durationMs / 1000

        var bestSong: Song? = null
        var bestScore = -100

        for (song in candidates) {
            var score = 0
            val candTitle = normalize(song.title)
            val candArtist = normalize(song.artist)

            // Exact or substring title match
            if (candTitle == normTitle) {
                score += 50
            } else if (candTitle.contains(normTitle) || normTitle.contains(candTitle)) {
                score += 30
            }

            // Artist match
            if (candArtist.contains(normArtist) || normArtist.contains(candArtist)) {
                score += 30
            }

            // Penalize live / cover / remix if original didn't ask for it
            if (!normTitle.contains("live") && candTitle.contains("live")) score -= 20
            if (!normTitle.contains("remix") && candTitle.contains("remix")) score -= 15
            if (!normTitle.contains("karaoke") && candTitle.contains("karaoke")) score -= 40
            if (!normTitle.contains("cover") && candTitle.contains("cover")) score -= 25

            // Duration closeness bonus
            if (targetDurationSec > 0 && song.durationSeconds > 0) {
                val diff = Math.abs(song.durationSeconds - targetDurationSec)
                if (diff <= 5) score += 20
                else if (diff <= 15) score += 10
                else if (diff > 60) score -= 20
            }

            if (score > bestScore) {
                bestScore = score
                bestSong = song
            }
        }

        return if (bestScore >= 20) bestSong else candidates.firstOrNull()
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("(?i)\\(feat\\..*?\\)|\\[feat\\..*?\\]"), "")
            .replace(Regex("(?i)\\(official video\\)|\\[official video\\]|\\(audio\\)|\\[audio\\]"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
    }
}
