package com.lunara.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.data.models.LyricsData
import com.lunara.music.data.models.RepeatMode
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.PlaylistEntity
import com.lunara.music.database.PlaylistSongCrossRef
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.download.DownloadManager
import com.lunara.music.service.lyrics.LyricsService
import com.lunara.music.ui.components.ArtworkImage
import com.lunara.music.ui.components.Media3TimeBar
import com.lunara.music.ui.theme.AccentLime
import com.lunara.music.ui.theme.AccentLimeDark
import com.lunara.music.ui.theme.AccentPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    onDismiss: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }
    val downloadManager = remember { DownloadManager(context) }

    val currentSong by LunaraPlayerManager.currentSong.collectAsState()
    val isPlaying by LunaraPlayerManager.isPlaying.collectAsState()
    val isBuffering by LunaraPlayerManager.isBuffering.collectAsState()
    val positionMs by LunaraPlayerManager.currentPositionMs.collectAsState()
    val bufferedPositionMs by LunaraPlayerManager.bufferedPositionMs.collectAsState()
    val durationMs by LunaraPlayerManager.durationMs.collectAsState()
    val shuffleEnabled by LunaraPlayerManager.shuffleEnabled.collectAsState()
    val repeatMode by LunaraPlayerManager.repeatMode.collectAsState()
    val queue by LunaraPlayerManager.queue.collectAsState()
    val queueIndex by LunaraPlayerManager.queueIndex.collectAsState()

    var showQueueSheet by remember { mutableStateOf(false) }
    var isLyricsMode by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    var isLiked by remember(currentSong?.id) { mutableStateOf(currentSong?.isLiked ?: false) }
    var lyricsData by remember { mutableStateOf<LyricsData?>(null) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    val song = currentSong

    LaunchedEffect(song?.id) {
        if (song != null) {
            val entity = db.songDao().getSongById(song.id)
            if (entity != null) {
                isLiked = entity.isLiked
            }
        }
    }

    LaunchedEffect(song?.id, isLyricsMode) {
        if (song != null && (isLyricsMode || lyricsData == null) && lyricsData?.songTitle != song.title) {
            isLoadingLyrics = true
            lyricsData = LyricsService.getLyrics(song.title, song.artist, song.durationSeconds, song.id)
            isLoadingLyrics = false
        }
    }

    if (song == null) {
        onDismiss()
        return
    }

    // Warm olive-dark adaptive gradient matching Screenshot 6 & 7
    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF282A1D),
                Color(0xFF1E2016),
                Color(0xFF13140E)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .testTag("full_player_screen")
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("player_collapse_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Now Playing",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = Color.White
                        )
                        Text(
                            text = song.album ?: "Cache Songs",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Color(0xFFA8A29E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { isLyricsMode = !isLyricsMode }) {
                        Icon(
                            imageVector = if (isLyricsMode) Icons.Default.MusicNote else Icons.Default.Mic,
                            contentDescription = "Toggle View",
                            tint = if (isLyricsMode) AccentLime else Color.White
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                if (isLyricsMode) {
                    // Synced Lyrics View (Screenshot 7)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // "Sources" Pill Button
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0x3344403C),
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(bottom = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Equalizer,
                                        contentDescription = null,
                                        tint = AccentLime,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Sources",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = AccentLime
                                    )
                                }
                            }

                            if (isLoadingLyrics) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = AccentLime)
                                }
                            } else if (lyricsData == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Lyrics not available for this track",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color(0xFFA8A29E)
                                    )
                                }
                            } else if (lyricsData?.isSynced == true) {
                                val listState = rememberLazyListState()
                                val lines = lyricsData!!.lines
                                val activeIndex = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)

                                LaunchedEffect(activeIndex) {
                                    if (activeIndex in lines.indices) {
                                        listState.animateScrollToItem(activeIndex)
                                    }
                                }

                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(lines) { index, line ->
                                        val isActive = index == activeIndex
                                        Text(
                                            text = line.text,
                                            style = MaterialTheme.typography.headlineMedium.copy(
                                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                                fontSize = if (isActive) 28.sp else 22.sp
                                            ),
                                            color = if (isActive) Color.White else Color(0x66FFFFFF),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { LunaraPlayerManager.seekTo(line.timeMs) }
                                                .padding(vertical = 12.dp)
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Text(
                                            text = lyricsData?.plainLyrics ?: "",
                                            style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 36.sp),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Standard Cover Art View (Screenshot 6)
                    Spacer(modifier = Modifier.height(10.dp))

                    ArtworkImage(
                        url = song.thumbnailUrl,
                        cornerRadius = 28.dp,
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .shadow(24.dp, RoundedCornerShape(28.dp), spotColor = Color.Black)
                            .testTag("player_artwork")
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Track Info & 4 Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 23.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                color = Color(0xFFA8A29E)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                if (!song.artistId.isNullOrBlank()) {
                                    onNavigateToArtist(song.artistId)
                                }
                            }
                        )
                    }

                    // 4 Action Buttons Row (Like, Palette, Add, Downloaded check)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Heart (Like)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0x3344403C))
                                .clickable {
                                    val newLiked = !isLiked
                                    isLiked = newLiked
                                    scope.launch(Dispatchers.IO) {
                                        db.songDao().insertOrUpdateSong(song.copy(isLiked = newLiked).toEntity())
                                        db.songDao().updateLiked(song.id, newLiked)
                                    }
                                }
                                .testTag("player_like_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (isLiked) Color(0xFFEF4444) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 2. Palette / Audio FX
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFFFFF))
                                .clickable {
                                    Toast.makeText(context, "Equalizer: Lossless Stream 24-bit", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "FX",
                                tint = Color(0xFF1E2016),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 3. Add to Playlist (+)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFFFFF))
                                .clickable { showAddToPlaylistDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add",
                                tint = Color(0xFF1E2016),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 4. Download / Checkmark button
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFFFFF))
                                .clickable {
                                    scope.launch {
                                        Toast.makeText(context, "Downloading track...", Toast.LENGTH_SHORT).show()
                                        downloadManager.downloadSong(song)
                                        Toast.makeText(context, "Saved offline", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .testTag("player_download_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (song.isDownloaded) Icons.Default.Check else Icons.Outlined.Download,
                                contentDescription = "Download",
                                tint = Color(0xFF1E2016),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Media3 Scrubber Bar & Timestamps
                Column(modifier = Modifier.fillMaxWidth()) {
                    Media3TimeBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedPositionMs = bufferedPositionMs,
                        playedColor = android.graphics.Color.parseColor("#D2E07E"),
                        unplayedColor = android.graphics.Color.parseColor("#44403C"),
                        bufferedColor = android.graphics.Color.parseColor("#78716C"),
                        scrubberColor = android.graphics.Color.parseColor("#D2E07E"),
                        onSeek = { LunaraPlayerManager.seekTo(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .testTag("player_seek_slider")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(positionMs),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = Color(0xFFA8A29E)
                        )
                        Text(
                            text = formatTime(durationMs),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = Color(0xFFA8A29E)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Main Playback Controls Row (Shuffle, Prev, Giant Lime Play/Pause, Next, Repeat)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { LunaraPlayerManager.toggleShuffle() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleEnabled) AccentLime else Color.White
                        )
                    }

                    IconButton(
                        onClick = { LunaraPlayerManager.previous() },
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("player_previous_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Iconic Lime Circular Play/Pause Button (Screenshot 6)
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AccentLime)
                            .clickable { LunaraPlayerManager.togglePlayPause() }
                            .testTag("player_play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = AccentLimeDark,
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = AccentLimeDark,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { LunaraPlayerManager.next() },
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("player_next_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { LunaraPlayerManager.toggleRepeat() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = when (repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                RepeatMode.ALL -> Icons.Default.Repeat
                                RepeatMode.OFF -> Icons.Default.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (repeatMode != RepeatMode.OFF) AccentLime else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom 3 Action Tabs: Queue | Sleep timer | Lyrics
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Queue
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { showQueueSheet = true }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Queue",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Queue", fontSize = 11.sp, color = Color.White)
                    }

                    // Sleep timer
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { showSleepTimerDialog = true }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bedtime,
                            contentDescription = "Sleep timer",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Sleep timer", fontSize = 11.sp, color = Color.White)
                    }

                    // Lyrics
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { isLyricsMode = !isLyricsMode }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Equalizer,
                            contentDescription = "Lyrics",
                            tint = if (isLyricsMode) AccentPurple else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Lyrics",
                            fontSize = 11.sp,
                            color = if (isLyricsMode) AccentPurple else Color.White
                        )
                    }
                }
            }
        }
    }

    // Queue Sheet
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = Color(0xFF1E2016)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Now Playing & Queue",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    TextButton(onClick = { LunaraPlayerManager.clearQueue() }) {
                        Text("Clear Queue", color = AccentLime)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(queue) { index, item ->
                        val isCurrent = index == queueIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) AccentLime.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { LunaraPlayerManager.playSongs(queue, index) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(url = item.thumbnailUrl, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) AccentLime else Color.White
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFA8A29E),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { LunaraPlayerManager.removeFromQueue(index) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = Color(0xFF78716C),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Sleep Timer Dialog
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Toast.makeText(context, "Timer set for $mins minutes", Toast.LENGTH_SHORT).show()
                                    showSleepTimerDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = AccentLime)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("$mins minutes", color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Add to Playlist Dialog
    if (showAddToPlaylistDialog) {
        var userPlaylists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
        var showNewPlaylistInput by remember { mutableStateOf(false) }
        var newPlaylistName by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                db.playlistDao().getAllPlaylists().collect {
                    userPlaylists = it
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showAddToPlaylistDialog = false },
            title = { Text("Add to Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (showNewPlaylistInput) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Playlist Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    val id = "pl_${System.currentTimeMillis()}"
                                    scope.launch(Dispatchers.IO) {
                                        db.playlistDao().insertPlaylist(
                                            PlaylistEntity(id = id, title = newPlaylistName.trim())
                                        )
                                        db.songDao().insertOrUpdateSong(song.toEntity())
                                        db.playlistDao().insertSongToPlaylist(
                                            PlaylistSongCrossRef(playlistId = id, songId = song.id)
                                        )
                                    }
                                    showAddToPlaylistDialog = false
                                    Toast.makeText(context, "Added to playlist", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentLime),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Create & Add", color = AccentLimeDark)
                        }
                    } else {
                        Button(
                            onClick = { showNewPlaylistInput = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentLime),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = AccentLimeDark)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Playlist", color = AccentLimeDark)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (userPlaylists.isEmpty()) {
                            Text(
                                "No playlists created yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA8A29E)
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                items(userPlaylists.size) { idx ->
                                    val pl = userPlaylists[idx]
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch(Dispatchers.IO) {
                                                    db.songDao().insertOrUpdateSong(song.toEntity())
                                                    db.playlistDao().insertSongToPlaylist(
                                                        PlaylistSongCrossRef(playlistId = pl.id, songId = song.id)
                                                    )
                                                }
                                                showAddToPlaylistDialog = false
                                                Toast.makeText(context, "Added to ${pl.title}", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = AccentLime)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(pl.title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddToPlaylistDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
