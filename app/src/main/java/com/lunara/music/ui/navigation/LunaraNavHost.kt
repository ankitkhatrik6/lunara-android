package com.lunara.music.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.ui.components.PersistentPlayerBar
import com.lunara.music.ui.screens.*
import com.lunara.music.ui.theme.BackgroundBlack

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Onboarding : Screen("onboarding")
    object NameSetup : Screen("name_setup")
    object Main : Screen("main")
    object LikedSongs : Screen("liked_songs")
    object Top20 : Screen("top_20")
    object Downloaded : Screen("downloaded")
    object LocalMusic : Screen("local_music")
    object Settings : Screen("settings")
    object YouTubeLogin : Screen("youtube_login")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/$playlistId"
    }
    object AlbumDetail : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: String) = "album_detail/$albumId"
    }
    object ArtistDetail : Screen("artist_detail/{artistId}") {
        fun createRoute(artistId: String) = "artist_detail/$artistId"
    }
}

enum class MainTab {
    HOME,
    SEARCH,
    LIBRARY
}

@Composable
fun LunaraNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var showFullPlayer by remember { mutableStateOf(false) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentSong by LunaraPlayerManager.currentSong.collectAsState()

    val isSubScreen = currentRoute != null &&
        currentRoute != Screen.Main.route &&
        currentRoute != Screen.Splash.route &&
        currentRoute != Screen.Onboarding.route &&
        currentRoute != Screen.NameSetup.route

    val showPersistentBarOnSubScreen = isSubScreen && currentSong != null

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(
                    onSplashFinished = { isOnboarded ->
                        if (isOnboarded) {
                            navController.navigate(Screen.Main.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Screen.Onboarding.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onFinishOnboarding = {
                        navController.navigate(Screen.NameSetup.route)
                    }
                )
            }

            composable(Screen.NameSetup.route) {
                NameSetupScreen(
                    onNameSaved = {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Main.route) {
                MainAppScaffold(
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToLikedSongs = { navController.navigate(Screen.LikedSongs.route) },
                    onNavigateToTop20 = { navController.navigate(Screen.Top20.route) },
                    onNavigateToDownloaded = { navController.navigate(Screen.Downloaded.route) },
                    onNavigateToLocalMusic = { navController.navigate(Screen.LocalMusic.route) },
                    onNavigateToPlaylist = { id -> navController.navigate(Screen.PlaylistDetail.createRoute(id)) },
                    onNavigateToAlbum = { id -> navController.navigate(Screen.AlbumDetail.createRoute(id)) },
                    onNavigateToArtist = { id -> navController.navigate(Screen.ArtistDetail.createRoute(id)) },
                    onOpenFullPlayer = { showFullPlayer = true }
                )
            }

            composable(Screen.LikedSongs.route) {
                LikedSongsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Top20.route) {
                Top20Screen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Downloaded.route) {
                DownloadedScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.LocalMusic.route) {
                LocalMusicScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToYouTubeLogin = { navController.navigate(Screen.YouTubeLogin.route) }
                )
            }

            composable(Screen.YouTubeLogin.route) {
                YouTubeLoginScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSignedIn = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId") ?: ""
                AlbumDetailScreen(
                    albumId = albumId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType })
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("artistId") ?: ""
                ArtistDetailScreen(
                    artistId = artistId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAlbum = { albId -> navController.navigate(Screen.AlbumDetail.createRoute(albId)) }
                )
            }
        }

        // Persistent Player Bar on Sub-Screens
        AnimatedVisibility(
            visible = showPersistentBarOnSubScreen,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 6.dp),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            PersistentPlayerBar(
                onExpand = { showFullPlayer = true },
                onNavigateToArtist = { artistId ->
                    navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                }
            )
        }

        // Modal Fullscreen Player
        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            FullPlayerScreen(
                onDismiss = { showFullPlayer = false },
                onNavigateToArtist = { artistId ->
                    showFullPlayer = false
                    navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                }
            )
            BackHandler {
                showFullPlayer = false
            }
        }
    }
}

@Composable
fun MainAppScaffold(
    onNavigateToSettings: () -> Unit,
    onNavigateToLikedSongs: () -> Unit,
    onNavigateToTop20: () -> Unit,
    onNavigateToDownloaded: () -> Unit,
    onNavigateToLocalMusic: () -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onOpenFullPlayer: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }
    val currentSong by LunaraPlayerManager.currentSong.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BackgroundBlack,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            ) {
                // Persistent Player Bar docked right above NavigationBar
                if (currentSong != null) {
                    PersistentPlayerBar(
                        onExpand = onOpenFullPlayer,
                        onNavigateToArtist = onNavigateToArtist
                    )
                }

                // Bottom Navigation Dock
                NavigationBar(
                    containerColor = Color.Black,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    NavigationBarItem(
                        selected = selectedTab == MainTab.HOME,
                        onClick = { selectedTab = MainTab.HOME },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == MainTab.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                                contentDescription = "Home"
                            )
                        },
                        label = {
                            Text(
                                "Home",
                                fontWeight = if (selectedTab == MainTab.HOME) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Color(0xFF262626),
                            unselectedIconColor = Color(0xFFA8A29E),
                            unselectedTextColor = Color(0xFFA8A29E)
                        ),
                        modifier = Modifier.testTag("nav_tab_home")
                    )

                    NavigationBarItem(
                        selected = selectedTab == MainTab.SEARCH,
                        onClick = { selectedTab = MainTab.SEARCH },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == MainTab.SEARCH) Icons.Filled.Search else Icons.Outlined.Search,
                                contentDescription = "Search"
                            )
                        },
                        label = {
                            Text(
                                "Search",
                                fontWeight = if (selectedTab == MainTab.SEARCH) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Color(0xFF262626),
                            unselectedIconColor = Color(0xFFA8A29E),
                            unselectedTextColor = Color(0xFFA8A29E)
                        ),
                        modifier = Modifier.testTag("nav_tab_search")
                    )

                    NavigationBarItem(
                        selected = selectedTab == MainTab.LIBRARY,
                        onClick = { selectedTab = MainTab.LIBRARY },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == MainTab.LIBRARY) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic,
                                contentDescription = "Library"
                            )
                        },
                        label = {
                            Text(
                                "Library",
                                fontWeight = if (selectedTab == MainTab.LIBRARY) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Color(0xFF262626),
                            unselectedIconColor = Color(0xFFA8A29E),
                            unselectedTextColor = Color(0xFFA8A29E)
                        ),
                        modifier = Modifier.testTag("nav_tab_library")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainTab.HOME -> HomeScreen(
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToAlbum = onNavigateToAlbum,
                    onNavigateToPlaylist = onNavigateToPlaylist,
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToSearch = { selectedTab = MainTab.SEARCH }
                )
                MainTab.SEARCH -> SearchScreen(
                    onNavigateToAlbum = onNavigateToAlbum,
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToPlaylist = onNavigateToPlaylist
                )
                MainTab.LIBRARY -> LibraryScreen(
                    onNavigateToLikedSongs = onNavigateToLikedSongs,
                    onNavigateToTop20 = onNavigateToTop20,
                    onNavigateToDownloaded = onNavigateToDownloaded,
                    onNavigateToLocalMusic = onNavigateToLocalMusic,
                    onNavigateToPlaylistDetail = onNavigateToPlaylist
                )
            }
        }
    }
}
