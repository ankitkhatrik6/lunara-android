package com.lunara.music.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY lastPlayedTimestamp DESC")
    fun getLikedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY lastPlayedTimestamp DESC")
    suspend fun getLikedSongsList(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC LIMIT 20")
    fun getTop20Songs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedTimestamp > 0 ORDER BY lastPlayedTimestamp DESC LIMIT 30")
    fun getRecentlyPlayed(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isDownloaded = 1 ORDER BY lastPlayedTimestamp DESC")
    fun getDownloadedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongsIfNotExist(songs: List<SongEntity>)

    @Query("UPDATE songs SET isLiked = :isLiked WHERE id = :id")
    suspend fun updateLiked(id: String, isLiked: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayedTimestamp = :timestamp WHERE id = :id")
    suspend fun recordPlay(id: String, timestamp: Long)

    @Query("UPDATE songs SET isDownloaded = :isDownloaded, localFilePath = :filePath, fileSize = :fileSize WHERE id = :id")
    suspend fun updateDownloadStatus(id: String, isDownloaded: Boolean, filePath: String?, fileSize: Long)

    @Query("UPDATE songs SET isDownloaded = 0, localFilePath = NULL WHERE id = :id")
    suspend fun removeDownload(id: String)

    @Query("UPDATE songs SET playCount = 0, lastPlayedTimestamp = 0")
    suspend fun clearHistory()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("UPDATE playlists SET title = :title WHERE id = :id")
    suspend fun renamePlaylist(id: String, title: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongToPlaylist(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.orderIndex ASC
    """)
    fun getSongsForPlaylist(playlistId: String): Flow<List<SongEntity>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.orderIndex ASC
    """)
    suspend fun getSongsForPlaylistOnce(playlistId: String): List<SongEntity>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    fun getSongCountForPlaylist(playlistId: String): Flow<Int>
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 15")
    fun getRecentSearches(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

@Dao
interface UserPrefDao {
    @Query("SELECT value FROM user_preferences WHERE `key` = :key")
    suspend fun getPref(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPref(pref: UserPrefEntity)
}
