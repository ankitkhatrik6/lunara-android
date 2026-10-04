package com.lunara.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Search
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
import androidx.core.content.ContextCompat
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.SongEntity
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.download.DownloadManager
import com.lunara.music.service.local.LocalMusicScanner
import com.lunara.music.ui.components.ArtworkImage
import com.lunara.music.ui.components.EmptyStateView
import com.lunara.music.ui.components.SongItemRow
import com.lunara.music.ui.theme.BackgroundDark
import com.lunara.music.ui.theme.PrimaryIndigo
import com.lunara.music.ui.theme.SurfaceElevatedDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    var likedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        db.songDao().getLikedSongs().collect { entities ->
            likedSongs = entities.map { Song.fromEntity(it) }
        }
    }

    val filteredSongs = remember(likedSongs, searchQuery) {
        if (searchQuery.isBlank()) likedSongs
        else likedSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("liked_songs_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Liked Songs", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search & Controls Row
            if (likedSongs.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search in liked songs") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceElevatedDark,
                        unfocusedContainerColor = SurfaceElevatedDark,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { LunaraPlayerManager.playSongs(filteredSongs, 0) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BackgroundDark)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play All (${filteredSongs.size})", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val shuffled = filteredSongs.shuffled()
                            LunaraPlayerManager.playSongs(shuffled, 0)
                        },
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

            if (filteredSongs.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.FavoriteBorder,
                    title = "No liked songs yet",
                    message = "Tap the heart icon on any song while listening to add it to your favorites."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredSongs) { song ->
                        SongItemRow(
                            song = song,
                            onSongClick = { LunaraPlayerManager.playSong(song, filteredSongs) },
                            onPlayNext = { LunaraPlayerManager.addNext(song) },
                            onAddToQueue = { LunaraPlayerManager.addToQueue(song) },
                            onLikeToggle = {
                                scope.launch(Dispatchers.IO) {
                                    db.songDao().updateLiked(song.id, false)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Top20Screen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val db = remember { LunaraDatabase.getDatabase(context) }
    var topSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    LaunchedEffect(Unit) {
        db.songDao().getTop20Songs().collect { entities ->
            topSongs = entities.map { Song.fromEntity(it) }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("top_20_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Your Top 20", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (topSongs.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.Leaderboard,
                    title = "No listening history yet",
                    message = "As you stream music with Lunara, your most-played songs will automatically rank here."
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { LunaraPlayerManager.playSongs(topSongs, 0) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BackgroundDark)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play All", color = BackgroundDark, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            LunaraPlayerManager.playSongs(topSongs.shuffled(), 0)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevatedDark),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Shuffle", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(topSongs) { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { LunaraPlayerManager.playSong(song, topSongs) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${index + 1}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = if (index < 3) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp)
                            )

                            ArtworkImage(url = song.thumbnailUrl, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.White
                                )
                                Text(
                                    text = "${song.artist} • ${song.playCount} plays",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }
    val downloadManager = remember { DownloadManager(context) }

    var downloadedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var totalSizeMb by remember { mutableStateOf(0.0) }

    fun refresh() {
        scope.launch {
            val size = withContext(Dispatchers.IO) { downloadManager.getTotalDownloadedSize() }
            totalSizeMb = size / (1024.0 * 1024.0)
        }
    }

    LaunchedEffect(Unit) {
        db.songDao().getDownloadedSongs().collect { entities ->
            downloadedSongs = entities.map { Song.fromEntity(it) }
            refresh()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("downloaded_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Downloaded", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (downloadedSongs.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.DownloadDone,
                    title = "No downloaded songs",
                    message = "Downloaded songs can be listened to 100% offline without internet."
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${downloadedSongs.size} songs • %.1f MB".format(totalSizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        scope.launch {
                            downloadManager.clearAllDownloads()
                            Toast.makeText(context, "Downloads cleared", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(downloadedSongs) { song ->
                        SongItemRow(
                            song = song,
                            onSongClick = { LunaraPlayerManager.playSong(song, downloadedSongs) },
                            onPlayNext = { LunaraPlayerManager.addNext(song) },
                            onAddToQueue = { LunaraPlayerManager.addToQueue(song) },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            downloadManager.deleteDownload(song.id)
                                            Toast.makeText(context, "Removed download", Toast.LENGTH_SHORT).show()
                                            refresh()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete download",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var hasPermission by remember {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        mutableStateOf(ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            scope.launch {
                isScanning = true
                localSongs = LocalMusicScanner.scanLocalMusic(context)
                isScanning = false
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isScanning = true
            localSongs = LocalMusicScanner.scanLocalMusic(context)
            isScanning = false
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("local_music_screen"),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Music on this Device", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasPermission) {
                EmptyStateView(
                    icon = Icons.Default.Folder,
                    title = "Storage permission needed",
                    message = "Allow Lunara to scan device storage for your personal audio files.",
                    actionButtonText = "Grant Permission",
                    onActionClick = {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        launcher.launch(perm)
                    }
                )
            } else if (isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            } else if (localSongs.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.FolderOpen,
                    title = "No music found on this device",
                    message = "Audio files saved in your device's Music folder will appear here automatically."
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${localSongs.size} tracks found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { LunaraPlayerManager.playSongs(localSongs, 0) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = BackgroundDark, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All", color = BackgroundDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(localSongs) { song ->
                        SongItemRow(
                            song = song,
                            onSongClick = { LunaraPlayerManager.playSong(song, localSongs) },
                            onPlayNext = { LunaraPlayerManager.addNext(song) },
                            onAddToQueue = { LunaraPlayerManager.addToQueue(song) }
                        )
                    }
                }
            }
        }
    }
}
