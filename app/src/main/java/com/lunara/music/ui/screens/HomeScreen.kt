package com.lunara.music.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.R
import com.lunara.music.data.models.Album
import com.lunara.music.data.models.Playlist
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.innertube.InnerTubeService
import com.lunara.music.ui.components.*
import com.lunara.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    var userName by remember { mutableStateOf("Music Lover") }
    var indianMusicSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var chillOutAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var recommendedPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    fun loadHomeData() {
        scope.launch {
            isLoading = true
            hasError = false

            withContext(Dispatchers.IO) {
                val storedName = db.userPrefDao().getPref("user_name")
                if (!storedName.isNullOrBlank()) {
                    userName = storedName
                }
            }

            try {
                // Fetch curated regional & trending music (Screenshot 1 "Indian Music" & "Chill Out")
                val searchIndian = InnerTubeService.search("Bollywood Hits")
                indianMusicSongs = searchIndian.songs.ifEmpty {
                    InnerTubeService.search("Arijit Singh Hits").songs
                }

                val homeData = InnerTubeService.getHome()
                val albums = homeData["albums"]?.filterIsInstance<Album>() ?: emptyList()
                val playlists = homeData["playlists"]?.filterIsInstance<Playlist>() ?: emptyList()

                chillOutAlbums = if (albums.isNotEmpty()) albums else searchIndian.albums
                recommendedPlaylists = if (playlists.isNotEmpty()) playlists else searchIndian.playlists

                isLoading = false
            } catch (e: Exception) {
                hasError = true
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadHomeData()
    }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 4..11 -> "Good Morning ☀️"
            in 12..16 -> "Good Afternoon ☀️"
            else -> "Good Evening 🌙"
        }
    }

    val moodChips = listOf("Energise", "Feel good", "Relax", "Work out", "Party", "Focus")

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        containerColor = BackgroundBlack,
        topBar = {
            // Top Bar with Profile, Logo/Title, and Settings Gear (Screenshot 1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Profile",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.lunara_logo),
                        contentDescription = "Lunara Logo",
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lunara",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = Color.White
                    )
                }

                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("home_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        if (isLoading && indianMusicSongs.isEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(top = 16.dp)
            ) {
                items(8) { SkeletonSongRow() }
            }
        } else if (hasError && indianMusicSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                EmptyStateView(
                    icon = Icons.Default.Refresh,
                    title = "Couldn't load music",
                    message = "Please check your connection and tap Retry.",
                    actionButtonText = "Retry",
                    onActionClick = { loadHomeData() }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // 1. Warm Amber Hero Banner with Girl Image (Screenshot 1)
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .height(180.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(HeroBannerAmber)
                    ) {
                        // Right Side Girl Image extending over banner
                        Image(
                            painter = painterResource(id = R.drawable.hero_banner),
                            contentDescription = "Enjoy the music",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(220.dp)
                                .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                        )

                        // Left Side Greeting & User Name
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .padding(start = 22.dp, top = 22.dp, bottom = 22.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = greeting,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    lineHeight = 28.sp
                                ),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = userName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Enjoy the music 🎵",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 2. Rounded Search Pill Bar (Screenshot 1)
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .clickable { onNavigateToSearch() },
                        color = Color(0xFF161616),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint = Color(0xFFA8A29E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Search songs, albums, artists...",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    color = Color(0xFFA8A29E)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice",
                                tint = Color(0xFFA8A29E),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 3. Mood / Activity Chips Row (Screenshot 1)
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(moodChips) { mood ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xFF1E1E1E),
                                modifier = Modifier.clickable { onNavigateToSearch() }
                            ) {
                                Text(
                                    text = mood,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFD6D3D1)
                                    ),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 4. Section: Indian Music (Screenshot 1)
                if (indianMusicSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Indian Music",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = Color(0xFFE59850),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(indianMusicSongs) { song ->
                                Column(
                                    modifier = Modifier
                                        .width(145.dp)
                                        .clickable { LunaraPlayerManager.playSong(song, indianMusicSongs) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(145.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    ) {
                                        ArtworkImage(
                                            url = song.thumbnailUrl,
                                            cornerRadius = 12.dp,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        // Play Badge overlay on top-left (Screenshot 1)
                                        Box(
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(Color(0x99000000)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.White
                                    )

                                    Text(
                                        text = song.artist,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp,
                                            color = Color(0xFFA8A29E)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                // 5. Section: Chill Out (Screenshot 1)
                if (chillOutAlbums.isNotEmpty()) {
                    item {
                        Text(
                            text = "Chill Out",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = Color(0xFFE59850),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(chillOutAlbums) { album ->
                                AlbumCard(
                                    album = album,
                                    onClick = { onNavigateToAlbum(album.id) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // 6. Section: Recommended Playlists
                if (recommendedPlaylists.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recommended for you",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = Color(0xFFE59850),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(recommendedPlaylists) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onNavigateToPlaylist(playlist.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
