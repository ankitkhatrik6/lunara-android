package com.lunara.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.PlaylistEntity
import com.lunara.music.database.PlaylistSongCrossRef
import com.lunara.music.database.SongEntity
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.spotify.SpotifyImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LunaraTests {

    private lateinit var db: LunaraDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LunaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testSpotifyPlaylistIdExtraction() {
        val url1 = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abcd1234"
        val id1 = SpotifyImporter.extractPlaylistId(url1)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", id1)

        val uri2 = "spotify:playlist:5ABkMt93Rh22GceqcnguuQ"
        val id2 = SpotifyImporter.extractPlaylistId(uri2)
        assertEquals("5ABkMt93Rh22GceqcnguuQ", id2)

        val invalid = "https://open.spotify.com/album/37i9dQZF1DXcBWIGoYBM5M"
        val idInvalid = SpotifyImporter.extractPlaylistId(invalid)
        assertNull(idInvalid)
    }

    @Test
    fun testSongFormattedDuration() {
        val song1 = Song(id = "1", title = "Track 1", artist = "Artist 1", durationSeconds = 0)
        assertEquals("0:00", song1.formattedDuration())

        val song2 = Song(id = "2", title = "Track 2", artist = "Artist 2", durationSeconds = 65)
        assertEquals("1:05", song2.formattedDuration())

        val song3 = Song(id = "3", title = "Track 3", artist = "Artist 3", durationSeconds = 240)
        assertEquals("4:00", song3.formattedDuration())
    }

    @Test
    fun testLikedSongsDatabaseFlow() = runBlocking {
        val song1 = SongEntity(id = "s1", title = "Blinding Lights", artist = "The Weeknd", isLiked = true, lastPlayedTimestamp = 1000)
        val song2 = SongEntity(id = "s2", title = "Starboy", artist = "The Weeknd", isLiked = false, lastPlayedTimestamp = 2000)

        db.songDao().insertOrUpdateSong(song1)
        db.songDao().insertOrUpdateSong(song2)

        val liked = db.songDao().getLikedSongs().first()
        assertEquals(1, liked.size)
        assertEquals("Blinding Lights", liked[0].title)

        // Toggle like on song2
        db.songDao().updateLiked("s2", true)
        val updatedLiked = db.songDao().getLikedSongs().first()
        assertEquals(2, updatedLiked.size)
    }

    @Test
    fun testTop20Calculation() = runBlocking {
        for (i in 1..25) {
            db.songDao().insertOrUpdateSong(
                SongEntity(
                    id = "id_$i",
                    title = "Song $i",
                    artist = "Artist",
                    playCount = i * 2,
                    lastPlayedTimestamp = System.currentTimeMillis()
                )
            )
        }

        val top20 = db.songDao().getTop20Songs().first()
        assertEquals(20, top20.size)
        // Highest play count should be ranked first
        assertEquals(50, top20[0].playCount)
        assertEquals("Song 25", top20[0].title)
    }

    @Test
    fun testPlaylistOperations() = runBlocking {
        val playlistId = "pl_custom_1"
        val playlist = PlaylistEntity(id = playlistId, title = "Late Night Drive", description = "Synthwave")
        db.playlistDao().insertPlaylist(playlist)

        val song = SongEntity(id = "s_drive", title = "Nightcall", artist = "Kavinsky")
        db.songDao().insertOrUpdateSong(song)
        db.playlistDao().insertSongToPlaylist(PlaylistSongCrossRef(playlistId = playlistId, songId = song.id, orderIndex = 0))

        val songsInPl = db.playlistDao().getSongsForPlaylist(playlistId).first()
        assertEquals(1, songsInPl.size)
        assertEquals("Nightcall", songsInPl[0].title)

        // Remove song
        db.playlistDao().removeSongFromPlaylist(playlistId, song.id)
        val afterRemove = db.playlistDao().getSongsForPlaylist(playlistId).first()
        assertEquals(0, afterRemove.size)

        // Delete playlist
        db.playlistDao().deletePlaylist(playlistId)
        val retrievedPl = db.playlistDao().getPlaylistById(playlistId)
        assertNull(retrievedPl)
    }

    @Test
    fun testPlayerQueueManager() {
        val songA = Song(id = "a", title = "A", artist = "Artist A")
        val songB = Song(id = "b", title = "B", artist = "Artist B")
        val songC = Song(id = "c", title = "C", artist = "Artist C")

        LunaraPlayerManager.playSongs(listOf(songA, songB), 0)
        assertEquals(2, LunaraPlayerManager.queue.value.size)
        assertEquals(0, LunaraPlayerManager.queueIndex.value)

        // Add next
        LunaraPlayerManager.addNext(songC)
        val queueAfterAddNext = LunaraPlayerManager.queue.value
        assertEquals(3, queueAfterAddNext.size)
        assertEquals("c", queueAfterAddNext[1].id)

        // Remove
        LunaraPlayerManager.removeFromQueue(1)
        assertEquals(2, LunaraPlayerManager.queue.value.size)

        // Clear queue
        LunaraPlayerManager.clearQueue()
        assertEquals(1, LunaraPlayerManager.queue.value.size) // retains current track
    }
}
