package com.lunara.music.data.models

import com.lunara.extractor.StreamQuality
import com.lunara.music.database.SongEntity

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val streamUrl: String? = null,
    val isLiked: Boolean = false,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val playCount: Int = 0,
    val isLocal: Boolean = false
) {
    fun formattedDuration(): String {
        if (durationSeconds <= 0) return "0:00"
        val m = durationSeconds / 60
        val s = durationSeconds % 60
        return "%d:%02d".format(m, s)
    }

    fun toEntity(): SongEntity {
        return SongEntity(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            album = album,
            albumId = albumId,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            streamUrl = streamUrl,
            isLiked = isLiked,
            isDownloaded = isDownloaded,
            localFilePath = localFilePath,
            playCount = playCount
        )
    }

    companion object {
        fun fromEntity(entity: SongEntity): Song {
            return Song(
                id = entity.id,
                title = entity.title,
                artist = entity.artist,
                artistId = entity.artistId,
                album = entity.album,
                albumId = entity.albumId,
                durationSeconds = entity.durationSeconds,
                thumbnailUrl = entity.thumbnailUrl,
                streamUrl = entity.streamUrl,
                isLiked = entity.isLiked,
                isDownloaded = entity.isDownloaded,
                localFilePath = entity.localFilePath,
                playCount = entity.playCount
            )
        }
    }
}

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val year: String? = null,
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val tracks: List<Song> = emptyList()
)

data class Artist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subscriberCount: String? = null,
    val topSongs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val relatedArtists: List<Artist> = emptyList()
)

data class Playlist(
    val id: String,
    val title: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val tracks: List<Song> = emptyList(),
    val isUserCreated: Boolean = false
)

data class SearchResult(
    val songs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList()
)

data class LyricsLine(
    val timeMs: Long,
    val text: String
)

data class LyricsData(
    val songTitle: String,
    val artist: String,
    val plainLyrics: String? = null,
    val lines: List<LyricsLine> = emptyList(),
    val isSynced: Boolean = false
)

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

enum class AudioQuality(val title: String, val bitrate: String) {
    LOW("Data Saver", "64 kbps"),
    NORMAL("Normal", "128 kbps"),
    HIGH("High Quality", "256 kbps")
}

/**
 * Maps the stored Audio Quality setting to the extractor's stream selection.
 *
 * The settings dialog writes human-readable labels ("Normal (128 kbps)"); this is
 * the one place that turns them into something the resolver acts on, so the label
 * on screen and the bytes on the wire cannot drift apart. An unknown or missing
 * value reads as NORMAL because that is the label the dialog shows when nothing
 * has been chosen yet — a setting that says one thing while playback does another
 * is worse than a boring default.
 */
fun streamQualityFor(setting: String?): StreamQuality = when {
    setting.isNullOrBlank() -> StreamQuality.NORMAL
    setting.startsWith("Data Saver") -> StreamQuality.LOW
    setting.startsWith("High") -> StreamQuality.HIGH
    setting.startsWith("Normal") -> StreamQuality.NORMAL
    else -> StreamQuality.NORMAL
}

/** The volume factor for one song under the Volume normalization setting.

 * InnerTune's exact rule, kept because it is measured against the same figure
 * YouTube reports: a track whose [loudnessDb] is above the target is attenuated by
 * that many decibels, and a track at or below it plays at unity — quiet songs are
 * never boosted, because boosting gain a quiet master was mixed that way on purpose
 * would only clip on phones that are already loud. Null loudness (no figure
 * reported, a local file) and a disabled setting both mean unity.
 */

fun normalizationGainFor(loudnessDb: Double?, enabled: Boolean): Float {
    if (!enabled || loudnessDb == null) return 1f
    val gain = 10.0.pow(-loudnessDb / 20.0)
    return minOf(gain, 1.0).toFloat()
}

data class BrowseCategory(
    val id: String,
    val title: String,
    val query: String,
    val iconName: String? = null,
    val tintColorHex: String = "#8E97FD"
)
