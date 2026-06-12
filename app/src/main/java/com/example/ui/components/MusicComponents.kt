package com.example.ui.components

import com.example.ui.theme.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LyricLine
import com.example.model.Track
import kotlinx.coroutines.launch

/**
 * Custom High-Fidelity Audio Rainbow Visualizer.
 * Displays moving neon color bars mapped in real-time to simulated sound frequencies.
 */
@Composable
fun AudioWaveVisualizer(
    spectrum: FloatArray,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barCount = spectrum.size
        val gap = 6f
        val totalGaps = (barCount - 1) * gap
        val barWidth = (width - totalGaps) / barCount

        val neonBrush = Brush.verticalGradient(
            colors = listOf(
                accentColor,
                accentColor.copy(alpha = 0.5f),
                accentColor.copy(alpha = 0.1f)
            )
        )

        for (i in 0 until barCount) {
            val magnitude = spectrum[i] // 0.1f to 1.0f
            val barHeight = magnitude * height
            val x = i * (barWidth + gap)
            val y = height - barHeight

            // Draw glowing background under glow
            drawRoundRect(
                color = accentColor.copy(alpha = 0.25f),
                topLeft = Offset(x, y - 4f),
                size = Size(barWidth, barHeight + 4f),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )

            // Draw primary solid bar
            drawRoundRect(
                brush = neonBrush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

/**
 * Generates an artistic geometric procedural artwork card for each track.
 * This guarantees a beautiful morning aesthetic without relying on external file images.
 */
@Composable
fun TrackArtworkPattern(
    track: Track,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    var isImageLoadFailed by remember(track.id) { mutableStateOf(track.coverUri.isNullOrEmpty()) }

    if (!isImageLoadFailed && track.coverUri != null) {
        val painter = coil.compose.rememberAsyncImagePainter(
            model = track.coverUri,
            onError = { isImageLoadFailed = true }
        )
        val state = painter.state
        if (state is coil.compose.AsyncImagePainter.State.Error) {
            isImageLoadFailed = true
        }

        if (!isImageLoadFailed) {
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = track.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = modifier
                    .clip(RoundedCornerShape(24.dp))
                    .aspectRatio(1f)
            )
            return
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "artwork_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val scalePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val startColor = Color(track.colorStart)
    val endColor = Color(track.colorEnd)

    val themeSurfaceColor = MaterialTheme.colorScheme.surface
    val themeBackgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(startColor, endColor, themeSurfaceColor),
                    radius = 350f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(26.dp)
        ) {
            val activeRotation = if (isPlaying) rotation else 15f
            val activeScale = if (isPlaying) scalePulse else 1f

            rotate(activeRotation) {
                // Outer ring
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(startColor, endColor, startColor)
                    ),
                    radius = (size.minDimension / 2.3f) * activeScale,
                    style = Stroke(width = 8f)
                )

                // Substar burst shapes
                val points = 8
                val center = Offset(size.width / 2, size.height / 2)
                val outerRadius = (size.minDimension / 3.4f) * activeScale
                val innerRadius = (size.minDimension / 6f) * activeScale

                for (idx in 0 until points) {
                    val angle1 = (idx * (2 * Math.PI / points)).toFloat()
                    val angle2 = ((idx + 0.5) * (2 * Math.PI / points)).toFloat()

                    val p1 = Offset(
                        center.x + outerRadius * Math.cos(angle1.toDouble()).toFloat(),
                        center.y + outerRadius * Math.sin(angle1.toDouble()).toFloat()
                    )
                    val p2 = Offset(
                        center.x + innerRadius * Math.cos(angle2.toDouble()).toFloat(),
                        center.y + innerRadius * Math.sin(angle2.toDouble()).toFloat()
                    )

                    drawLine(
                        color = startColor.copy(alpha = 0.8f),
                        start = center,
                        end = p1,
                        strokeWidth = 6f
                    )
                    drawLine(
                        color = endColor.copy(alpha = 0.6f),
                        start = center,
                        end = p2,
                        strokeWidth = 4f
                    )
                }

                // Core metallic disc
                drawCircle(
                    color = themeBackgroundColor.copy(alpha = 0.9f),
                    radius = size.minDimension / 7f
                )

                drawCircle(
                     brush = Brush.linearGradient(
                         colors = listOf(startColor, endColor)
                     ),
                    radius = size.minDimension / 12f
                )
            }
        }
    }
}

/**
 * Synced interactive lyrics pane.
 * Automatically scrolls to keep the current running lyric line perfectly centered and styled.
 */
@Composable
fun LyricsPane(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Instrumental Ambient",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
        return
    }

    // Find the currently active index
    val activeIdx = lyrics.indexOfLast { currentPositionMs >= it.timeMs }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Smooth scrolls list to current active index center whenever there is an index switch
    LaunchedEffect(activeIdx) {
        if (lyrics.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(
                    index = if (activeIdx > 1) activeIdx - 1 else 0
                )
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics) { idx, lyric ->
            val isActive = idx == activeIdx
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1.08f else 0.92f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
            )
            val textColor = if (isActive) accentColor else TextSecondary.copy(alpha = 0.5f)
            val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            
            Text(
                text = lyric.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = if (isActive) 22.sp else 18.sp,
                    lineHeight = 28.sp
                ),
                color = textColor,
                fontWeight = fontWeight,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .drawBehind {
                        if (isActive) {
                            // Pulsing backdrop shadow glow
                            drawCircle(
                                color = accentColor.copy(alpha = 0.08f),
                                radius = size.width / 4,
                                center = center
                            )
                        }
                    }
            )
        }
    }
}
