package com.lunara.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.data.models.BrowseCategory
import com.lunara.music.data.models.SearchResult
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.database.SearchHistoryEntity
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.innertube.InnerTubeService
import com.lunara.music.ui.components.*
import com.lunara.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    var searchQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchResult by remember { mutableStateOf<SearchResult?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    var recentSearches by remember { mutableStateOf<List<SearchHistoryEntity>>(emptyList()) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            db.searchHistoryDao().getRecentSearches().collect {
                recentSearches = it
            }
        }
    }

    // Categories matching Screenshot 4
    val categories = remember {
        listOf(
            CategoryData("Charts", "Top Hits Global", CatCharts),
            CategoryData("New release\nalbums", "New Albums", CatNewRelease),
            CategoryData("Chill", "Chill Vibes", CatChill),
            CategoryData("Commute", "Road Trip Hits", CatCommute),
            CategoryData("Energize", "Energy Workout", CatEnergize),
            CategoryData("Feel-good", "Happy Hits", CatFeelGood),
            CategoryData("Focus", "Deep Focus Study", CatFocus),
            CategoryData("Gaming", "Gaming Soundtracks", CatGaming),
            CategoryData("Party", "Dance Party", CatParty),
            CategoryData("Romance", "Romantic Melodies", CatRomance),
            CategoryData("Sad", "Sad Melancholy", CatSad),
            CategoryData("Sleep", "Relaxing Piano Sleep", CatSleep)
        )
    }

    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        searchQuery = trimmed
        suggestions = emptyList()
        isSearching = true

        scope.launch {
            withContext(Dispatchers.IO) {
                db.searchHistoryDao().insertSearch(SearchHistoryEntity(query = trimmed))
            }
            try {
                val res = InnerTubeService.search(trimmed)
                searchResult = res
            } catch (e: Exception) {
                searchResult = SearchResult()
            } finally {
                isSearching = false
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_screen"),
        containerColor = BackgroundBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Top Search Pill Bar with Back & Language Globe (Screenshot 4)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotEmpty() || searchResult != null) {
                                searchQuery = ""
                                searchResult = null
                                suggestions = emptyList()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    // Rounded Search Input Pill
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { newText ->
                            searchQuery = newText
                            if (newText.isBlank()) {
                                suggestions = emptyList()
                                searchResult = null
                            } else {
                                debounceJob?.cancel()
                                debounceJob = scope.launch {
                                    delay(300)
                                    suggestions = InnerTubeService.getSearchSuggestions(newText)
                                }
                            }
                        },
                        placeholder = {
                            Text(
                                "Search YouTube Music...",
                                color = Color(0xFFA8A29E),
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint = Color(0xFFA8A29E)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    suggestions = emptyList()
                                    searchResult = null
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFA8A29E)
                                    )
                                }
                            } else {
                                IconButton(onClick = {}) {
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = "Language",
                                        tint = Color(0xFFA8A29E)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF333333),
                            unfocusedBorderColor = Color(0xFF222222),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF161616),
                            unfocusedContainerColor = Color(0xFF161616)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_input_field")
                    )
                }

                // Filter Chips when search results exist
                if (searchResult != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val filters = listOf("All", "Songs", "Artists", "Albums", "Playlists")
                        items(filters) { filter ->
                            val isSelected = selectedCategoryFilter == filter
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedCategoryFilter = filter },
                                label = { Text(filter) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentLime,
                                    selectedLabelColor = AccentLimeDark,
                                    containerColor = Color(0xFF222222),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isSearching) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(8) { SkeletonSongRow() }
                }
            } else if (suggestions.isNotEmpty() && searchResult == null) {
                // Auto-complete suggestions list
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(suggestions) { sug ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { executeSearch(sug) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = Color(0xFFA8A29E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = sug,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            } else if (searchResult != null) {
                // Search Results
                val res = searchResult!!
                val hasAnyResults = res.songs.isNotEmpty() || res.artists.isNotEmpty() || res.albums.isNotEmpty() || res.playlists.isNotEmpty()

                if (!hasAnyResults) {
                    EmptyStateView(
                        icon = Icons.Outlined.Search,
                        title = "No results found",
                        message = "We couldn't find anything matching \"$searchQuery\"."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        // Songs
                        if ((selectedCategoryFilter == "All" || selectedCategoryFilter == "Songs") && res.songs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Songs",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                            val songsToShow = if (selectedCategoryFilter == "All") res.songs.take(6) else res.songs
                            items(songsToShow) { song ->
                                SongItemRow(
                                    song = song,
                                    onSongClick = { LunaraPlayerManager.playSong(song, res.songs) },
                                    onPlayNext = { LunaraPlayerManager.addNext(song) },
                                    onAddToQueue = { LunaraPlayerManager.addToQueue(song) },
                                    onLikeToggle = {
                                        scope.launch(Dispatchers.IO) {
                                            val newLiked = !song.isLiked
                                            db.songDao().insertOrUpdateSong(song.copy(isLiked = newLiked).toEntity())
                                            db.songDao().updateLiked(song.id, newLiked)
                                        }
                                    }
                                )
                            }
                        }

                        // Artists
                        if ((selectedCategoryFilter == "All" || selectedCategoryFilter == "Artists") && res.artists.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Artists",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(res.artists) { artist ->
                                        Column(
                                            modifier = Modifier
                                                .width(110.dp)
                                                .clickable { onNavigateToArtist(artist.id) },
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            ArtworkImage(
                                                url = artist.thumbnailUrl,
                                                cornerRadius = 55.dp,
                                                modifier = Modifier
                                                    .size(100.dp)
                                                    .clip(CircleShape)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Albums
                        if ((selectedCategoryFilter == "All" || selectedCategoryFilter == "Albums") && res.albums.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Albums",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(res.albums) { album ->
                                        AlbumCard(album = album, onClick = { onNavigateToAlbum(album.id) })
                                    }
                                }
                            }
                        }

                        // Playlists
                        if ((selectedCategoryFilter == "All" || selectedCategoryFilter == "Playlists") && res.playlists.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Playlists",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(res.playlists) { playlist ->
                                        PlaylistCard(playlist = playlist, onClick = { onNavigateToPlaylist(playlist.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Browse Screen with 2-Column Grid (Screenshot 4)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text(
                            text = "Browse",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // 2-Column Grid of 12 Colorful Category Cards
                    for (i in categories.indices step 2) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    BrowseCardView(
                                        category = categories[i],
                                        onClick = { executeSearch(categories[i].searchKeyword) }
                                    )
                                }
                                if (i + 1 < categories.size) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        BrowseCardView(
                                            category = categories[i + 1],
                                            onClick = { executeSearch(categories[i + 1].searchKeyword) }
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class CategoryData(
    val title: String,
    val searchKeyword: String,
    val color: Color
)

@Composable
fun BrowseCardView(
    category: CategoryData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(105.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("browse_card_${category.searchKeyword}"),
        shape = RoundedCornerShape(16.dp),
        color = category.color
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Title on Top-Left
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    lineHeight = 20.sp
                ),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
            )

            // Overlapping Fanned Mini Cards on Bottom-Right (Screenshot 4)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .rotate(15f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x40000000))
                )
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .offset(x = (-20).dp)
                        .rotate(28f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x66000000))
                )
            }
        }
    }
}
