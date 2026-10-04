package com.lunara.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lunara.music.data.models.Album
import com.lunara.music.data.models.Playlist
import com.lunara.music.data.models.Song
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.ui.theme.AccentLime
import com.lunara.music.ui.theme.AccentLimeDark
import com.lunara.music.ui.theme.PrimaryIndigo

@Composable
fun ArtworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    contentDescription: String? = "Album artwork"
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(0xFF18181B)),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = contentDescription,
                tint = Color(0xFF52525B),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun SongItemRow(
    song: Song,
    onSongClick: () -> Unit,
    onLikeToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val currentSong by LunaraPlayerManager.currentSong.collectAsState()
    val isPlaying by LunaraPlayerManager.isPlaying.collectAsState()
    val isCurrentSong = currentSong?.id == song.id

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrentSong) Color(0x14D2E07E) else Color.Transparent)
            .clickable(onClick = onSongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("song_item_${song.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center
        ) {
            ArtworkImage(
                url = song.thumbnailUrl,
                cornerRadius = 10.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        1.dp,
                        if (isCurrentSong) AccentLime.copy(alpha = 0.5f) else Color(0x1AFFFFFF),
                        RoundedCornerShape(10.dp)
                    )
            )

            // Equalizer badge overlay if this song is playing
            if (isCurrentSong) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x73000000)),
                    contentAlignment = Alignment.Center
                ) {
                    LiveAudioVisualizer(
                        isPlaying = isPlaying,
                        barCount = 3,
                        barWidth = 2.5.dp,
                        barMaxHeight = 16.dp,
                        color = AccentLime
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrentSong) AccentLime else Color.White
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = Color(0xFFA8A29E)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (song.formattedDuration() != "0:00") {
                    Text(
                        text = " • ${song.formattedDuration()}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = Color(0xFF71717A)
                        )
                    )
                }
            }
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            if (onLikeToggle != null) {
                IconButton(
                    onClick = onLikeToggle,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("like_button_${song.id}")
                ) {
                    Icon(
                        imageVector = if (song.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (song.isLiked) "Unlike" else "Like",
                        tint = if (song.isLiked) Color(0xFFEF4444) else Color(0xFF71717A),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Song options",
                        tint = Color(0xFF71717A),
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        onClick = {
                            showMenu = false
                            onPlayNext?.invoke()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        onClick = {
                            showMenu = false
                            onAddToQueue?.invoke()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        onClick = {
                            showMenu = false
                            onAddToPlaylist?.invoke()
                        }
                    )
                    if (onDownloadClick != null) {
                        DropdownMenuItem(
                            text = { Text(if (song.isDownloaded) "Downloaded" else "Download") },
                            onClick = {
                                showMenu = false
                                onDownloadClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}")
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(16.dp))
                .shadow(12.dp, RoundedCornerShape(16.dp), spotColor = Color.Black)
        ) {
            ArtworkImage(
                url = album.thumbnailUrl,
                cornerRadius = 16.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
            )

            // Play badge on bottom right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .border(1.dp, Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Album",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                color = Color(0xFFA8A29E)
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("playlist_card_${playlist.id}")
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(16.dp))
                .shadow(12.dp, RoundedCornerShape(16.dp), spotColor = Color.Black)
        ) {
            ArtworkImage(
                url = playlist.thumbnailUrl,
                cornerRadius = 16.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
            )

            // Play badge on bottom right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .border(1.dp, Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Playlist",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = playlist.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )
        Text(
            text = playlist.description ?: "${playlist.trackCount} songs",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                color = Color(0xFFA8A29E)
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SkeletonSongRow(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslateAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF18181B),
            Color(0xFF27272A),
            Color(0xFF18181B)
        ),
        start = Offset(shimmerTranslateAnim - 200f, shimmerTranslateAnim - 200f),
        end = Offset(shimmerTranslateAnim, shimmerTranslateAnim)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(shimmerBrush)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmerBrush)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush)
            )
        }
    }
}

@Composable
fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF18181B))
                .border(1.dp, Color(0x22FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFA8A29E),
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            ),
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color(0xFFA8A29E),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionButtonText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onActionClick,
                colors = ButtonDefaults.buttonColors(containerColor = AccentLime),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(actionButtonText, color = AccentLimeDark, fontWeight = FontWeight.Bold)
            }
        }
    }
}
