package com.lunara.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.ui.theme.PrimaryIndigo
import com.lunara.music.ui.theme.SurfaceElevatedDark

@Composable
fun MiniPlayer(
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSong by LunaraPlayerManager.currentSong.collectAsState()
    val isPlaying by LunaraPlayerManager.isPlaying.collectAsState()
    val isBuffering by LunaraPlayerManager.isBuffering.collectAsState()
    val currentPos by LunaraPlayerManager.currentPositionMs.collectAsState()
    val duration by LunaraPlayerManager.durationMs.collectAsState()

    val song = currentSong ?: return

    val progress = if (duration > 0) (currentPos.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenFullPlayer)
            .testTag("mini_player"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Column {
            // Subtle progress indicator on top edge
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = PrimaryIndigo,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    url = song.thumbnailUrl,
                    modifier = Modifier.size(46.dp),
                    cornerRadius = 8.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(2.dp),
                        strokeWidth = 2.dp,
                        color = PrimaryIndigo
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    IconButton(
                        onClick = { LunaraPlayerManager.togglePlayPause() },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("mini_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { LunaraPlayerManager.next() },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("mini_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next song",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
