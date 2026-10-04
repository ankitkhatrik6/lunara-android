package com.lunara.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.ui.theme.AccentLime
import com.lunara.music.ui.theme.AccentLimeDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Persistent BottomSheet / Bar in the main layout.
 * Displays currently playing song metadata, cover art, and playback control buttons
 * (play/pause, skip, progress bar) using AndroidX Media3 UI components.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersistentPlayerBar(
    onExpand: () -> Unit,
    onNavigateToArtist: ((String) -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaraDatabase.getDatabase(context) }

    val currentSong by LunaraPlayerManager.currentSong.collectAsState()
    val isPlaying by LunaraPlayerManager.isPlaying.collectAsState()
    val isBuffering by LunaraPlayerManager.isBuffering.collectAsState()
    val positionMs by LunaraPlayerManager.currentPositionMs.collectAsState()
    val bufferedPositionMs by LunaraPlayerManager.bufferedPositionMs.collectAsState()
    val durationMs by LunaraPlayerManager.durationMs.collectAsState()

    val song = currentSong ?: return

    var isLiked by remember(song.id) { mutableStateOf(song.isLiked) }

    LaunchedEffect(song.id) {
        val entity = db.songDao().getSongById(song.id)
        if (entity != null) {
            isLiked = entity.isLiked
        }
    }

    // Vinyl spinning rotation when playing
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Persistent BottomSheet pill container with swipe up gesture to expand
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = Color.Black,
                spotColor = Color(0x99000000)
            )
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1C1B1F),
                        Color(0xFF131215)
                    )
                )
            )
            .border(1.dp, Color(0x2E44403C), RoundedCornerShape(26.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -15f) {
                        onExpand() // Swipe up opens full player sheet
                    }
                }
            }
            .clickable(onClick = onExpand)
            .testTag("persistent_player_bar")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Drag handle affordance at top of BottomSheet bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color(0x40FFFFFF))
                )
            }

            // Main Bar Row: Cover Art, Metadata, and Playback Control Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Cover Art (Vinyl disc with center play badge or rounded squircle)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .clickable { LunaraPlayerManager.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    ArtworkImage(
                        url = song.thumbnailUrl,
                        cornerRadius = 12.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(if (isPlaying) rotation else 0f)
                    )

                    // Subtle Dark Overlay with Play/Pause state or Buffering
                    if (isBuffering) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x80000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AccentLime,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 2. Currently Playing Song's Metadata: Title, Artist, & Live Visualizer
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                ) {
                    // Title with basicMarquee for smooth marquee text scrolling
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                        modifier = Modifier.basicMarquee()
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = Color(0xFFA8A29E)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable {
                                    if (!song.artistId.isNullOrBlank()) {
                                        onNavigateToArtist?.invoke(song.artistId)
                                    }
                                }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Live audio visualizer equalizer indicator
                        LiveAudioVisualizer(
                            isPlaying = isPlaying,
                            barCount = 3,
                            barWidth = 2.dp,
                            barMaxHeight = 11.dp,
                            color = AccentLime
                        )
                    }
                }

                // 3. Playback Control Buttons: Skip Previous, Play/Pause, Skip Next, Like
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Like / Favorite button
                    IconButton(
                        onClick = {
                            val newLiked = !isLiked
                            isLiked = newLiked
                            scope.launch(Dispatchers.IO) {
                                db.songDao().insertOrUpdateSong(song.copy(isLiked = newLiked).toEntity())
                                db.songDao().updateLiked(song.id, newLiked)
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("persistent_like_button")
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) Color(0xFFEF4444) else Color(0xFFB0B0B0),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Skip Previous Button
                    IconButton(
                        onClick = { LunaraPlayerManager.previous() },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("persistent_prev_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color(0xFFD6D3D1),
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Play/Pause Button (Accent Lime Circle)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(AccentLime)
                            .clickable { LunaraPlayerManager.togglePlayPause() }
                            .testTag("persistent_play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = AccentLimeDark,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = AccentLimeDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Skip Next Button (Explicitly requested by user)
                    IconButton(
                        onClick = { LunaraPlayerManager.next() },
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("persistent_skip_next_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Skip",
                            tint = Color(0xFFD6D3D1),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }

            // 4. Media3 UI Progress Bar (DefaultTimeBar) embedded directly in persistent bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .height(6.dp)
            ) {
                Media3MiniTimeBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = bufferedPositionMs,
                    onSeek = { LunaraPlayerManager.seekTo(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .testTag("persistent_media3_timebar")
                )
            }
        }
    }
}
