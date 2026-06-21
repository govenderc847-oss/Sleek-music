package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    if (isImageLoadFailed || track.coverUri == null) {
        val startColor = Color(track.colorStart)
        val endColor = Color(track.colorEnd)
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            startColor.copy(alpha = 0.85f),
                            endColor.copy(alpha = 0.85f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = size.minDimension / 2.2f
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.03f),
                    radius = size.minDimension / 1.5f
                )
            }
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.MusicNote,
                contentDescription = "Music Track fallback icon",
                tint = Color.White,
                modifier = Modifier.size(86.dp)
            )
        }
        return
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagStudioPane(
    track: Track,
    tagUpdateStatus: String?,
    onSaveTags: (lyricsText: String, imageUri: android.net.Uri?) -> Unit,
    onClearStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rawLyrics by remember(track.id) {
        val initialText = if (track.lyrics.any { it.text.contains("[Local file:") }) {
            ""
        } else {
            track.lyrics.joinToString("\n") { it.text }
        }
        mutableStateOf(initialText)
    }

    var pickedImageUri by remember(track.id) { mutableStateOf<android.net.Uri?>(null) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            pickedImageUri = uri
        }
    }

    val accentColor = Color(track.colorStart)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ID3 TAG STUDIO",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            // 1. Artwork selector section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp))
                            .clickable { launcher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        val activeImageModel = pickedImageUri ?: track.coverUri
                        if (activeImageModel != null) {
                            androidx.compose.foundation.Image(
                                painter = coil.compose.rememberAsyncImagePainter(model = activeImageModel),
                                contentDescription = "Cover Image Preview",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Edit photo",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Default Music Icon",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "NO COVER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Embed Custom Album Art",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (pickedImageUri != null) "New art prepared to save" else "MP3 file uses default artwork",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (pickedImageUri != null) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { launcher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(max = 32.dp)
                        ) {
                            Text(
                                "Choose Photo",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 2. Lyrics Text Field section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Song Lyrics Editor",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                OutlinedTextField(
                    value = rawLyrics,
                    onValueChange = { rawLyrics = it },
                    placeholder = {
                        Text(
                            "Type or paste song lyrics here...\nSupports synchronized tags like:\n[00:15] Verse 1 lyrics\n[00:32] Chorus lyrics",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }

            // 3. Save Trigger Button
            Button(
                onClick = { onSaveTags(rawLyrics, pickedImageUri) },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save metadata",
                        tint = Color.White
                    )
                    Text(
                        text = "SAVE & BURN TO MP3",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // 4. Overlays / Notification banners for state changes
        if (tagUpdateStatus != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth(0.95f),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = tagUpdateStatus,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (tagUpdateStatus.contains("success") || tagUpdateStatus.contains("Error") || tagUpdateStatus.contains("Failed")) {
                        TextButton(
                            onClick = onClearStatus,
                            colors = ButtonDefaults.textButtonColors(contentColor = accentColor)
                        ) {
                            Text("DISMISS")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            color = accentColor,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}
