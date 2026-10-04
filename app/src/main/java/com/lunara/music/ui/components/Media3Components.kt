package com.lunara.music.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.lunara.music.ui.theme.AccentLime

/**
 * Media3 UI DefaultTimeBar for the Full Player Screen.
 * Provides interactive scrubbing, buffered position, and styled thumb scrubber.
 */
@Composable
fun Media3TimeBar(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    modifier: Modifier = Modifier,
    playedColor: Int = AndroidColor.parseColor("#D2E07E"), // Signature pale lime
    unplayedColor: Int = AndroidColor.parseColor("#262626"),
    bufferedColor: Int = AndroidColor.parseColor("#444444"),
    scrubberColor: Int = AndroidColor.parseColor("#FFFFFF"),
    onSeek: (Long) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DefaultTimeBar(ctx).apply {
                setPlayedColor(playedColor)
                setUnplayedColor(unplayedColor)
                setBufferedColor(bufferedColor)
                setScrubberColor(scrubberColor)
                addListener(object : TimeBar.OnScrubListener {
                    override fun onScrubStart(timeBar: TimeBar, position: Long) {}
                    override fun onScrubMove(timeBar: TimeBar, position: Long) {}
                    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                        if (!canceled) {
                            onSeek(position)
                        }
                    }
                })
            }
        },
        update = { timeBar ->
            timeBar.setDuration(durationMs.coerceAtLeast(0L))
            timeBar.setPosition(positionMs.coerceAtLeast(0L))
            timeBar.setBufferedPosition(bufferedPositionMs.coerceAtLeast(0L))
        }
    )
}

/**
 * Sleek, slender Media3 UI DefaultTimeBar embedded in the Persistent BottomSheet / Bar.
 * Allows interactive scrubbing directly on the persistent player bar with zero lag.
 */
@Composable
fun Media3MiniTimeBar(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    modifier: Modifier = Modifier,
    playedColor: Int = AndroidColor.parseColor("#D2E07E"),
    unplayedColor: Int = AndroidColor.parseColor("#222222"),
    bufferedColor: Int = AndroidColor.parseColor("#383838"),
    scrubberColor: Int = AndroidColor.parseColor("#D2E07E"),
    onSeek: (Long) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DefaultTimeBar(ctx).apply {
                setPlayedColor(playedColor)
                setUnplayedColor(unplayedColor)
                setBufferedColor(bufferedColor)
                setScrubberColor(scrubberColor)
                addListener(object : TimeBar.OnScrubListener {
                    override fun onScrubStart(timeBar: TimeBar, position: Long) {}
                    override fun onScrubMove(timeBar: TimeBar, position: Long) {}
                    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                        if (!canceled) {
                            onSeek(position)
                        }
                    }
                })
            }
        },
        update = { timeBar ->
            timeBar.setDuration(durationMs.coerceAtLeast(0L))
            timeBar.setPosition(positionMs.coerceAtLeast(0L))
            timeBar.setBufferedPosition(bufferedPositionMs.coerceAtLeast(0L))
        }
    )
}

/**
 * Animated real-time equalizer bars giving the UI a lively, high-end feel.
 */
@Composable
fun LiveAudioVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    barWidth: Dp = 2.5.dp,
    barMaxHeight: Dp = 14.dp,
    color: Color = AccentLime
) {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer")

    val anim1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val anim2 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(310, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val anim3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val anim4 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    Row(
        modifier = modifier.height(barMaxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = listOf(
            if (isPlaying) anim1 else 0.25f,
            if (isPlaying) anim2 else 0.45f,
            if (isPlaying) anim3 else 0.25f,
            if (isPlaying) anim4 else 0.35f
        )
        for (i in 0 until barCount) {
            val h = heights[i % heights.size]
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barMaxHeight * h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}
