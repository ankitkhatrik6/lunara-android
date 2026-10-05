package com.lunara.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.data.models.Album
import com.lunara.music.data.models.Artist
import com.lunara.music.data.models.Playlist
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.PlaylistEntity
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.innertube.InnerTubeService
import com.lunara.music.ui.components.*
import com.lunara.music.ui.theme.BackgroundDark
import com.lunara.music.ui.theme.PrimaryIndigo
import com.lunara.music.ui.theme.SurfaceElevatedDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    var playlistInfo by remember { mutableStateOf<PlaylistEntity?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isInnerTubePlaylist by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(playlistId) {
        isLoading = true
        if (playlistId.startsWith("VL") || playlistId.startsWith("RDAMPL") || playlistId.startsWith("PL")) {
            isInnerTubePlaylist = true
            val remotePl = InnerTubeService.getPlaylist(playlistId)
            if (remotePl != null) {
                playlistInfo = PlaylistEntity(
                    id = remotePl.id,
                    title = remotePl.title,
                    description = remotePl.description,
                    thumbnailUrl = remotePl.thumbnailUrl
                )
                songs = remotePl.tracks
            }
        } else {
            // Local Database playlist. Use the one-shot query: collecting the
            // Flow here never returns, so isLoading stayed true forever and the
            // screen sat on a spinner showing "0 songs".
            withContext(Dispatchers.IO) {
                playlistInfo = db.playlistDao().getPlaylistById(playlistId)
                songs = db.playlistDao().getSongsForPlaylistOnce(playlistId)
                    .map { Song.fromEntity(it) }
            }
        }
        isLoading = false
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("playlist_detail_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(playlistInfo?.title ?: "Playlist", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (!isInnerTubePlaylist && playlistInfo != null) {
                        IconButton(onClick = {
                            renameText = playlistInfo!!.title
                            showRenameDialog = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.White)
                        }
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                db.playlistDao().deletePlaylist(playlistId)
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
                                    onNavigateBack()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryIndigo)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // Header
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ArtworkImage(
                            url = playlistInfo?.thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl,
                            cornerRadius = 12.dp,
                            modifier = Modifier.size(160.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = playlistInfo?.title ?: "Playlist",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        if (!playlistInfo?.description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = playlistInfo!!.description!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${songs.size} tracks",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryIndigo
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (songs.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { LunaraPlayerManager.playSongs(songs, 0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BackgroundDark)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Play All", color = BackgroundDark, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { LunaraPlayerManager.playSongs(songs.shuffled(), 0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevatedDark),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Shuffle", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (songs.isEmpty()) {
                    item {
                        EmptyStateView(
                            icon = Icons.Default.QueueMusic,
                            title = "This playlist is empty",
                            message = "Add songs to this playlist from search or while listening."
                        )
                    }
                } else {
                    itemsIndexed(songs) { index, song ->
                        SongItemRow(
                            song = song,
                            onSongClick = { LunaraPlayerManager.playSong(song, songs) },
                            onPlayNext = { LunaraPlayerManager.addNext(song) },
                            onAddToQueue = { LunaraPlayerManager.addToQueue(song) },
                            trailingContent = if (!isInnerTubePlaylist) {
                                {
                                    IconButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                db.playlistDao().removeSongFromPlaylist(playlistId, song.id)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove from playlist",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newTitle = renameText.trim()
                        if (newTitle.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                db.playlistDao().renamePlaylist(playlistId, newTitle)
                                playlistInfo = playlistInfo?.copy(title = newTitle)
                            }
                            showRenameDialog = false
                            Toast.makeText(context, "Renamed playlist", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var album by remember { mutableStateOf<Album?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(albumId) {
        isLoading = true
        album = InnerTubeService.getAlbum(albumId)
        isLoading = false
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("album_detail_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(album?.title ?: "Album", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryIndigo)
            }
        } else if (album == null) {
            EmptyStateView(
                icon = Icons.Default.Album,
                title = "Couldn't load album",
                message = "The requested album could not be loaded."
            )
        } else {
            val alb = album!!
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ArtworkImage(
                            url = alb.thumbnailUrl,
                            cornerRadius = 14.dp,
                            modifier = Modifier.size(170.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = alb.title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = alb.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${alb.trackCount} tracks ${alb.year?.let { "• $it" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryIndigo
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (alb.tracks.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { LunaraPlayerManager.playSongs(alb.tracks, 0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BackgroundDark)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Play All", color = BackgroundDark, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { LunaraPlayerManager.playSongs(alb.tracks.shuffled(), 0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevatedDark),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Shuffle", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                itemsIndexed(alb.tracks) { index, track ->
                    SongItemRow(
                        song = track,
                        onSongClick = { LunaraPlayerManager.playSong(track, alb.tracks) },
                        onPlayNext = { LunaraPlayerManager.addNext(track) },
                        onAddToQueue = { LunaraPlayerManager.addToQueue(track) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistId: String,
    onNavigateBack: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var artist by remember { mutableStateOf<Artist?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(artistId) {
        isLoading = true
        artist = InnerTubeService.getArtist(artistId)
        isLoading = false
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("artist_detail_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(artist?.name ?: "Artist", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryIndigo)
            }
        } else if (artist == null) {
            EmptyStateView(
                icon = Icons.Default.Person,
                title = "Couldn't load artist",
                message = "The artist details could not be retrieved."
            )
        } else {
            val art = artist!!
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ArtworkImage(
                            url = art.thumbnailUrl,
                            cornerRadius = 75.dp,
                            modifier = Modifier
                                .size(150.dp)
                                .clip(RoundedCornerShape(75.dp))
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = art.name,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        if (!art.subscriberCount.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${art.subscriberCount} listeners",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (art.topSongs.isNotEmpty()) {
                            Button(
                                onClick = { LunaraPlayerManager.playSongs(art.topSongs, 0) },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(0.6f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BackgroundDark)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play Artist", color = BackgroundDark, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (art.topSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Popular Songs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(art.topSongs) { song ->
                        SongItemRow(
                            song = song,
                            onSongClick = { LunaraPlayerManager.playSong(song, art.topSongs) },
                            onPlayNext = { LunaraPlayerManager.addNext(song) },
                            onAddToQueue = { LunaraPlayerManager.addToQueue(song) }
                        )
                    }
                }

                if (art.albums.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Albums & Singles",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(art.albums) { alb ->
                                AlbumCard(
                                    album = alb,
                                    onClick = { onNavigateToAlbum(alb.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
