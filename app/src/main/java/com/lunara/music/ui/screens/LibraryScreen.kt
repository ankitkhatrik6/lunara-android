package com.lunara.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.PlaylistEntity
import com.lunara.music.database.PlaylistSongCrossRef
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.spotify.SpotifyImportProgress
import com.lunara.music.service.spotify.SpotifyImporter
import com.lunara.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToLikedSongs: () -> Unit,
    onNavigateToTop20: () -> Unit,
    onNavigateToDownloaded: () -> Unit,
    onNavigateToLocalMusic: () -> Unit,
    onNavigateToPlaylistDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    var likedCount by remember { mutableStateOf(0) }
    var downloadedCount by remember { mutableStateOf(0) }
    var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showSpotifyImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.songDao().getLikedSongs().collect {
            likedCount = it.size
        }
    }

    LaunchedEffect(Unit) {
        db.songDao().getDownloadedSongs().collect {
            downloadedCount = it.size
        }
    }

    LaunchedEffect(Unit) {
        db.playlistDao().getAllPlaylists().collect {
            playlists = it
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen"),
        containerColor = BackgroundBlack,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp
                    ),
                    color = Color.White
                )

                // Green Spotify Pill Button (Screenshot 5)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF0D2818),
                    modifier = Modifier
                        .clickable { showSpotifyImportDialog = true }
                        .testTag("spotify_import_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Green circular icon
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1DB954)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Import from Spotify",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = Color(0xFF1DB954)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Liked Card (Magenta / Rose)
            item {
                FeaturedLibraryCard(
                    title = "Liked",
                    subtitle = "$likedCount songs",
                    icon = Icons.Default.Favorite,
                    containerColor = CardColorLiked,
                    height = 130.dp,
                    onClick = onNavigateToLikedSongs,
                    testTag = "lib_liked_songs"
                )
            }

            // 2. Your Top 50 Card (Vibrant Orange)
            item {
                FeaturedLibraryCard(
                    title = "Your Top 50",
                    subtitle = "Most played songs from your history",
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    containerColor = CardColorTop50,
                    height = 125.dp,
                    onClick = onNavigateToTop20,
                    testTag = "lib_top_20"
                )
            }

            // 3. Row of 2 Cards: Cached & Downloaded
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        FeaturedLibraryCard(
                            title = "Cached",
                            subtitle = "Stream cache",
                            icon = Icons.Default.Sync,
                            containerColor = CardColorCached,
                            height = 115.dp,
                            onClick = onNavigateToDownloaded,
                            testTag = "lib_cached"
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        FeaturedLibraryCard(
                            title = "Downloaded",
                            subtitle = "$downloadedCount tracks",
                            icon = Icons.Default.Download,
                            containerColor = CardColorDownloaded,
                            height = 115.dp,
                            onClick = onNavigateToDownloaded,
                            testTag = "lib_downloaded"
                        )
                    }
                }
            }

            // 4. Music on this device (Emerald Green)
            item {
                FeaturedLibraryCard(
                    title = "Music on this device",
                    subtitle = "Look for music stored on this phone",
                    icon = Icons.Default.LibraryMusic,
                    containerColor = CardColorDevice,
                    height = 115.dp,
                    onClick = onNavigateToLocalMusic,
                    testTag = "lib_local_music"
                )
            }

            // 5. Uploaded Card (Rich Purple)
            item {
                FeaturedLibraryCard(
                    title = "Uploaded",
                    subtitle = "Personal cloud collection",
                    icon = Icons.Default.CloudUpload,
                    containerColor = CardColorUploaded,
                    height = 105.dp,
                    onClick = onNavigateToLocalMusic,
                    testTag = "lib_uploaded"
                )
            }

            // User Playlists Section Header
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    TextButton(onClick = { showCreatePlaylistDialog = true }) {
                        Text("Create", color = AccentLime)
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                items(playlists) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF161616))
                            .clickable { onNavigateToPlaylistDetail(playlist.id) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF262626)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = AccentLime,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = playlist.description ?: "Custom Playlist",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA8A29E)
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = Color(0xFF6E6E6E),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }

    // Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                newPlaylistName = ""
            },
            title = { Text("New Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentLime,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                val id = "pl_${System.currentTimeMillis()}"
                                db.playlistDao().insertPlaylist(
                                    PlaylistEntity(id = id, title = name)
                                )
                            }
                            showCreatePlaylistDialog = false
                            newPlaylistName = ""
                            Toast.makeText(context, "Playlist created", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentLime)
                ) {
                    Text("Create", color = AccentLimeDark)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePlaylistDialog = false
                    newPlaylistName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Spotify Import Dialog
    if (showSpotifyImportDialog) {
        SpotifyImportDialog(
            onDismiss = { showSpotifyImportDialog = false },
            onImportSuccess = { newPlaylistId ->
                showSpotifyImportDialog = false
                onNavigateToPlaylistDetail(newPlaylistId)
            }
        )
    }
}

@Composable
fun FeaturedLibraryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = containerColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.75f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(34.dp)
            )
        }
    }
}

@Composable
fun SpotifyImportDialog(
    onDismiss: () -> Unit,
    onImportSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    var spotifyUrl by remember { mutableStateOf("") }
    var progressState by remember { mutableStateOf<SpotifyImportProgress?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import Spotify Playlist", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (progressState == null && !isImporting) {
                    Text(
                        text = "Paste a public Spotify playlist link to match and import its songs directly into Lunara.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = spotifyUrl,
                        onValueChange = { spotifyUrl = it },
                        placeholder = { Text("https://open.spotify.com/playlist/...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentLime,
                            unfocusedBorderColor = Color(0xFF333333),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val p = progressState
                    if (p != null) {
                        Text(
                            text = p.stage,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (p.total > 0) {
                            val fraction = p.current.toFloat() / p.total
                            LinearProgressIndicator(
                                progress = { fraction },
                                color = AccentLime,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${p.current} of ${p.total} tracks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            CircularProgressIndicator(color = AccentLime)
                        }

                        if (p.currentTrackTitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = p.currentTrackTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        if (p.isComplete) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Import Summary", fontWeight = FontWeight.Bold, color = Color.White)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Total tracks: ${p.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Matched: ${p.matchedCount}", color = Color(0xFF34D399))
                                    Text("Not found: ${p.unmatchedCount}", color = Color(0xFFEF4444))
                                }
                            }
                        }

                        if (p.errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = p.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (progressState?.isComplete == true) {
                Button(
                    onClick = {
                        val p = progressState ?: return@Button
                        val playlistId = "pl_spotify_${System.currentTimeMillis()}"
                        scope.launch(Dispatchers.IO) {
                            db.playlistDao().insertPlaylist(
                                PlaylistEntity(
                                    id = playlistId,
                                    title = p.playlistName.ifBlank { "Imported Spotify Playlist" },
                                    description = "Imported ${p.matchedSongs.size} tracks from Spotify"
                                )
                            )
                            p.matchedSongs.forEachIndexed { idx, song ->
                                db.songDao().insertOrUpdateSong(song.toEntity())
                                db.playlistDao().insertSongToPlaylist(
                                    PlaylistSongCrossRef(playlistId = playlistId, songId = song.id, orderIndex = idx)
                                )
                            }
                            launch(Dispatchers.Main) {
                                onImportSuccess(playlistId)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentLime)
                ) {
                    Text("View Playlist", color = AccentLimeDark)
                }
            } else if (!isImporting) {
                Button(
                    onClick = {
                        if (spotifyUrl.isNotBlank()) {
                            isImporting = true
                            scope.launch {
                                SpotifyImporter.importSpotifyPlaylist(spotifyUrl).collect { progress ->
                                    progressState = progress
                                    if (progress.isComplete || progress.errorMessage != null) {
                                        isImporting = false
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentLime)
                ) {
                    Text("Start Import", color = AccentLimeDark)
                }
            }
        },
        dismissButton = {
            if (!isImporting) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

