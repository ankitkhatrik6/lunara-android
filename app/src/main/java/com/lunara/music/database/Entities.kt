package com.lunara.music.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val streamUrl: String? = null,
    val isLiked: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedTimestamp: Long = 0L,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val fileSize: Long = 0L
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isUserCreated: Boolean = true
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongCrossRef(
    val playlistId: String,
    val songId: String,
    val orderIndex: Int = 0
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_preferences")
data class UserPrefEntity(
    @PrimaryKey val key: String,
    val value: String
)
