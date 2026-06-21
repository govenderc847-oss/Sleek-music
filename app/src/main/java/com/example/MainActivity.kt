package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.model.Track
import com.example.player.PlaybackState
import kotlinx.coroutines.delay
import com.example.ui.components.AudioWaveVisualizer
import com.example.ui.components.LyricsPane
import com.example.ui.components.TrackArtworkPattern
import com.example.ui.theme.*
import com.example.ui.viewmodel.*
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Core local persistent Room Database setup
        val database = MusicDatabase.getDatabase(applicationContext)
        val repository = MusicRepository(database.musicDao())

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    MainScreen(
                        repository = repository,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: MusicRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    // 2. Instantiate custom core VM
    val viewModel: MusicViewModel = viewModel(
        factory = MusicViewModelFactory(application, repository)
    )

    // Collect reactive flows from ViewModel
    val rawTracks by viewModel.tracks.collectAsStateWithLifecycle()
    val filteredTracks by viewModel.filteredTracks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val amplitudeSpectrum by viewModel.amplitudeSpectrum.collectAsStateWithLifecycle()
    val isShuffle by viewModel.isShuffle.collectAsStateWithLifecycle()
    val isRepeatOne by viewModel.isRepeatOne.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle()
    val equalizerSettings by viewModel.equalizerSettings.collectAsStateWithLifecycle()
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val tagUpdateStatus by viewModel.tagUpdateStatus.collectAsStateWithLifecycle()

    val currentViewedPlaylist by viewModel.currentViewedPlaylist.collectAsStateWithLifecycle()
    val playlistDetailTracks by viewModel.playlistTracks.collectAsStateWithLifecycle()

    // 3. UI Screen States
    var activeTab by remember { mutableStateOf("Library") }
    var expandedPlayer by remember { mutableStateOf(false) }

    // On-device audio scanning every time the user enters/resumes the app
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.scanDeviceStorage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Onboarding status flows
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val storageStatus by viewModel.storageStatus.collectAsStateWithLifecycle()
    val isOnboardingActive by viewModel.isOnboardingActive.collectAsStateWithLifecycle()

    // Dialog triggering states
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var playlistDialogInput by remember { mutableStateOf("") }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

    // Genre List Constants
    val genres = listOf("All", "Synthwave", "Ambient", "Electronic", "Jazz", "Chillout")

    // Android Storage scanning launcher with permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.scanDeviceStorage()
    }

    val triggerStorageScan = {
        val permissionStr = if (android.os.Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_AUDIO"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        permissionLauncher.launch(permissionStr)
    }

    // Determine Greeting (morning, afternoon, evening vibe)
    val greetingMessage = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 0..11 -> "Good morning, beats ☀️"
            in 12..16 -> "Afternoon sound waves ☕"
            else -> "Evening soundscapes 🌙"
        }
    }

    // Onboarding overlay dialog
    if (isOnboardingActive) {
        SleekOnboardingWizard(
            onComplete = { viewModel.completeOnboarding() },
            onActivateScan = { triggerStorageScan() }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentTrack != null) 158.dp else 80.dp)
        ) {
            // A. Morning Greeting Header & Branding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = greetingMessage,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                }

                IconButton(onClick = { viewModel.resetOnboarding() }) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = "Tutorial Walkthrough",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // B. Conditional Playlist Detail View or Screen Router Views
            if (currentViewedPlaylist != null) {
                // Playlist Detail Screen Layer
                PlaylistDetailHeader(
                    playlist = currentViewedPlaylist!!,
                    onBack = { viewModel.viewPlaylistDetail(null) },
                    onDelete = {
                        viewModel.deletePlaylist(currentViewedPlaylist!!.id)
                    }
                )

                if (playlistDetailTracks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "Empty",
                                tint = Color.Gray,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No tracks in this playlist yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Gray
                            )
                            Text(
                                text = "Try adding a track from the library!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.DarkGray
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(playlistDetailTracks) { track ->
                            TrackRowItem(
                                track = track,
                                isPlayingNow = currentTrack?.id == track.id && (playbackState is PlaybackState.Playing),
                                isFavorite = rawTracks.firstOrNull { it.track.id == track.id }?.isFavorite == true,
                                onRowClick = {
                                    viewModel.selectAndPlay(track, contextList = playlistDetailTracks)
                                },
                                onToggleFav = { viewModel.toggleFavorite(track.id) },
                                onDeleteFromPlaylist = {
                                    viewModel.removeTrackFromPlaylist(currentViewedPlaylist!!.id, track.id)
                                }
                            )
                        }
                    }
                }
            } else {
                // Standard Tab Content Router based on bottom activeTab
                when (activeTab) {
                    "Library" -> {
                        // Search Text Box
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            placeholder = { Text("Search songs, artists, albums...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        )

                        // Horizontal Scrolling Genre Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .padding(start = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(genres) { genre ->
                                        val selected = selectedGenre == genre
                                        FilterChip(
                                            selected = selected,
                                            onClick = { viewModel.selectGenre(genre) },
                                            label = { Text(genre) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                selectedLabelColor = MaterialTheme.colorScheme.primary
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Large Featured Highlight Hero Card (only shown when not searching)
                        if (searchQuery.isEmpty() && selectedGenre == "All" && filteredTracks.isNotEmpty()) {
                            val heroTrack = filteredTracks.first().track
                            FeaturedSpecialHeroCard(
                                track = heroTrack,
                                isPlaying = currentTrack?.id == heroTrack.id && (playbackState is PlaybackState.Playing),
                                onClickPlay = {
                                    viewModel.selectAndPlay(heroTrack, contextList = filteredTracks.map { it.track })
                                }
                            )
                        }

                        // Standard Songs List
                        Text(
                            text = "TRACKS LIBRARY",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )

                        if (filteredTracks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No tracks matching query.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(filteredTracks) { uiTrack ->
                                    val track = uiTrack.track
                                    val isFav = uiTrack.isFavorite
                                    TrackRowItem(
                                        track = track,
                                        isPlayingNow = currentTrack?.id == track.id && (playbackState is PlaybackState.Playing),
                                        isFavorite = isFav,
                                        onRowClick = {
                                            viewModel.selectAndPlay(track, contextList = filteredTracks.map { it.track })
                                        },
                                        onToggleFav = { viewModel.toggleFavorite(track.id) }
                                    )
                                }
                            }
                        }
                    }

                    "Playlists" -> {
                        // Playlists Custom Tab Layout
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "YOUR MORNING PLAYLISTS",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        letterSpacing = 1.5.sp
                                    )

                                    Button(
                                        onClick = { showAddPlaylistDialog = true },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Add")
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("New")
                                    }
                                }

                                if (userPlaylists.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Default.QueueMusic,
                                                contentDescription = "Empty Playlist",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(54.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "Create your first playlist",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TextSecondary
                                            )
                                            Text(
                                                text = "Organize ambient tracks for morning meditation.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 30.dp)
                                            )
                                        }
                                    }
                                } else {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(2),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        items(userPlaylists) { playlist ->
                                            PlaylistGridCard(
                                                playlist = playlist,
                                                onCardClick = {
                                                    viewModel.viewPlaylistDetail(playlist)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "Equalizer" -> {
                        AdvancedEqualizerPage(
                            settings = equalizerSettings,
                            spectrum = amplitudeSpectrum,
                            onPresetSelected = { viewModel.updateEqualizerPreset(it) },
                            onBandUpdated = { idx, level -> viewModel.updateEqualizerBand(idx, level) },
                            onBassTweak = { viewModel.updateBassBoost(it) },
                            onSpatialTweak = { viewModel.updateSpatialAudio(it) },
                            onReverbTweak = { viewModel.updateReverb(it) },
                            onVocalTweak = { viewModel.updateVocalClarity(it) }
                        )
                    }

                    "Storage" -> {
                        DeviceStoragePage(
                            isScanning = isScanning,
                            statusText = storageStatus,
                            onTriggerScan = { triggerStorageScan() },
                            onResetOnboarding = { viewModel.resetOnboarding() },
                            onImportFolder = { uri -> viewModel.importTracksFromFolderUri(context, uri) },
                            onImportFiles = { uris -> viewModel.importTracksFromUris(context, uris) },
                            onClearImported = { viewModel.clearCustomImportedTracks(context) },
                            importedTracksCount = viewModel.loadCustomTracksFromPrefs(context).size
                        )
                    }
                }
            }
        }

        // C. Clean Floating Persistent COLLAPSED Mini Player
        if (currentTrack != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 90.dp)
            ) {
                MiniPlayerCard(
                    track = currentTrack!!,
                    playbackState = playbackState,
                    currentPosition = currentPosition,
                    amplitudeSpectrum = amplitudeSpectrum,
                    onToggle = { viewModel.togglePlayPause() },
                    onNext = { viewModel.playNext() },
                    onClickExpand = { expandedPlayer = true }
                )
            }
        }

        // Sleek Material 3 Bottom Navigation bar selector
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            SleekBottomNavigationBar(
                selectedTab = activeTab,
                onTabSelected = { tab ->
                    activeTab = tab
                    viewModel.viewPlaylistDetail(null)
                }
            )
        }

        // D. Fullscreen EXPANDED Slide-Up Player Drawer/Sheet
        AnimatedVisibility(
            visible = expandedPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            currentTrack?.let { track ->
                ExpandedPlayerView(
                    track = track,
                    playbackState = playbackState,
                    currentPosition = currentPosition,
                    amplitudeSpectrum = amplitudeSpectrum,
                    isShuffle = isShuffle,
                    isRepeatOne = isRepeatOne,
                    equalizerSettings = equalizerSettings,
                    userPlaylists = userPlaylists,
                    sleepTimerRemaining = sleepTimerRemaining,
                    onSetSleepTimer = { viewModel.startSleepTimer(it) },
                    onCollapse = { expandedPlayer = false },
                    onToggle = { viewModel.togglePlayPause() },
                    onNext = { viewModel.playNext() },
                    onPrev = { viewModel.playPrevious() },
                    onSeek = { viewModel.seekTo(it) },
                    onFavToggle = { viewModel.toggleFavorite(track.id) },
                    isFavorite = rawTracks.firstOrNull { it.track.id == track.id }?.isFavorite == true,
                    onShuffleToggle = { viewModel.toggleShuffle() },
                    onRepeatToggle = { viewModel.toggleRepeatOne() },
                    onAddToPlaylist = { showAddToPlaylistSheet = true },
                    onTweakBass = { viewModel.updateBassBoost(it) },
                    onTweakSpatial = { viewModel.updateSpatialAudio(it) },
                    onTweakClarify = { viewModel.updateVocalClarity(it) },
                    tagUpdateStatus = tagUpdateStatus,
                    onSaveTags = { trackId, lyricsText, imageUri ->
                        viewModel.updateTrackLyricsAndCover(context, trackId, lyricsText, imageUri)
                    },
                    onClearTagStatus = { viewModel.clearTagUpdateStatus() }
                )
            }
        }

        // E. Room Dialog Triggering Elements
        if (showAddPlaylistDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddPlaylistDialog = false
                    playlistDialogInput = ""
                },
                title = { Text("Create Morning Playlist") },
                text = {
                    OutlinedTextField(
                        value = playlistDialogInput,
                        onValueChange = { playlistDialogInput = it },
                        placeholder = { Text("e.g. Ambient Chill, Focus Session") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (playlistDialogInput.isNotBlank()) {
                                viewModel.createPlaylist(playlistDialogInput)
                                showAddPlaylistDialog = false
                                playlistDialogInput = ""
                            }
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPlaylistDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showAddToPlaylistSheet && currentTrack != null) {
            AlertDialog(
                onDismissRequest = { showAddToPlaylistSheet = false },
                title = { Text("Add Track to Playlist") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        Text(
                            text = "Choose a customized Morning Playlist for \"${currentTrack?.title}\":",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (userPlaylists.isEmpty()) {
                            Text(
                                "No custom playlists found. Go to the Playlists tab to create one!",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(userPlaylists) { playlist ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                viewModel.addTrackToPlaylist(playlist.id, currentTrack!!.id)
                                                showAddToPlaylistSheet = false
                                            }
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(playlist.name, fontWeight = FontWeight.Bold)
                                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Add")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showAddToPlaylistSheet = false }) {
                        Text("Dismiss")
                    }
                }
            )
        }
    }
}

// ======================== SUB-VIEW COMPOSABLES ========================

@Composable
fun PlaylistDetailHeader(
    playlist: UserPlaylist,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "Playlist",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete Playlist",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun FeaturedSpecialHeroCard(
    track: Track,
    isPlaying: Boolean,
    onClickPlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .shadow(12.dp, shape = RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(track.colorStart), Color(track.colorEnd)),
                    )
                )
        ) {
            // High fidelity graphics overlay
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Procedural disc pattern showing inside Hero
                TrackArtworkPattern(
                    track = track,
                    isPlaying = isPlaying,
                    modifier = Modifier.size(100.dp)
                )

                Spacer(modifier = Modifier.width(18.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "SUNRISE PICK",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Ambient modern pill toggle play
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onClickPlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isPlayingNow: Boolean,
    isFavorite: Boolean,
    onRowClick: () -> Unit,
    onToggleFav: () -> Unit,
    onDeleteFromPlaylist: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isPlayingNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            .clickable { onRowClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Rotating artwork pattern or colored circle
            TrackArtworkPattern(
                track = track,
                isPlaying = isPlayingNow,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlayingNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlayingNow) {
                // Inline tiny visualizer wave indicator
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Playing State",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) Color.Red else Color.LightGray
                )
            }

            if (onDeleteFromPlaylist != null) {
                IconButton(onClick = onDeleteFromPlaylist) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove From Playlist",
                        tint = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistGridCard(
    playlist: UserPlaylist,
    onCardClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clickable { onCardClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.QueueMusic,
                    contentDescription = "Playlist",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Column {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Persisted Playlist",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun MiniPlayerCard(
    track: Track,
    playbackState: PlaybackState,
    currentPosition: Long,
    amplitudeSpectrum: FloatArray,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClickExpand: () -> Unit
) {
    val progress = (currentPosition.toFloat() / track.durationMs).coerceIn(0f, 1f)
    val isPlaying = playbackState is PlaybackState.Playing

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clickable { onClickExpand() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    TrackArtworkPattern(
                        track = track,
                        isPlaying = isPlaying,
                        modifier = Modifier.size(46.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Small inline visualizer waves
                    AudioWaveVisualizer(
                        spectrum = amplitudeSpectrum.take(8).toFloatArray(), // Only use first 8 frequencies for compact visual
                        accentColor = Color(track.colorStart),
                        modifier = Modifier
                            .width(28.dp)
                            .height(20.dp)
                            .padding(end = 6.dp)
                    )

                    IconButton(onClick = onToggle) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "PlayPause",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // High precision loading linear progress tracker line at bottom of Card
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
                color = Color(track.colorStart),
                trackColor = Color.Transparent
            )
        }
    }
}

@Composable
fun ExpandedPlayerView(
    track: Track,
    playbackState: PlaybackState,
    currentPosition: Long,
    amplitudeSpectrum: FloatArray,
    isShuffle: Boolean,
    isRepeatOne: Boolean,
    equalizerSettings: EqualizerSettings,
    userPlaylists: List<UserPlaylist>,
    sleepTimerRemaining: Long?,
    onSetSleepTimer: (Int) -> Unit,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onFavToggle: () -> Unit,
    isFavorite: Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onTweakBass: (Float) -> Unit,
    onTweakSpatial: (Float) -> Unit,
    onTweakClarify: (Float) -> Unit,
    tagUpdateStatus: String?,
    onSaveTags: (String, String, android.net.Uri?) -> Unit,
    onClearTagStatus: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }

    val isPlaying = playbackState is PlaybackState.Playing
    val progress = (currentPosition.toFloat() / track.durationMs).coerceIn(0f, 1f)

    // Formatted positions values
    val currentSecs = (currentPosition / 1000) % 60
    val currentMins = (currentPosition / 1000) / 60
    val totalSecs = (track.durationMs / 1000) % 60
    val totalMins = (track.durationMs / 1000) / 60

    val elapsedTimeStr = String.format("%d:%02d", currentMins, currentSecs)
    val totalTimeStr = String.format("%d:%02d", totalMins, totalSecs)

    // Expanded view options: "Artwork", "Lyrics", "Equalizer FX"
    var activePanel by remember { mutableStateOf("Artwork") }
    var showSleepTimerMenu by remember { mutableStateOf(false) }

    // Floating Sleep Timer Dialog Interface
    if (showSleepTimerMenu) {
        Dialog(onDismissRequest = { showSleepTimerMenu = false }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sleep Timer Auto-Pause",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (sleepTimerRemaining != null) {
                            val mins = (sleepTimerRemaining / 1000) / 60
                            val secs = (sleepTimerRemaining / 1000) % 60
                            String.format("Active countdown: %d:%02d remaining", mins, secs)
                        } else {
                            "Automatically pause your music playback after a preset snooze duration."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    val presets = listOf(
                        "Cancel Timer" to 0,
                        "5 Minutes" to 5,
                        "15 Minutes" to 15,
                        "30 Minutes" to 30,
                        "60 Minutes" to 60
                    )
                    presets.forEach { (label, minutes) ->
                        TextButton(
                            onClick = {
                                onSetSleepTimer(minutes)
                                showSleepTimerMenu = false
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            val isCurrent = (minutes == 0 && sleepTimerRemaining == null) || 
                                            (minutes > 0 && sleepTimerRemaining != null && Math.abs((sleepTimerRemaining / 60000.0) - minutes) < 1.0)
                            Text(
                                text = label,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // E1. Swipe down handle and Collapse controller bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = "NOW PLAYING",
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Sleep Timer Button Action
                IconButton(onClick = { showSleepTimerMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = if (sleepTimerRemaining != null) MaterialTheme.colorScheme.primary else Color.White
                    )
                }

                IconButton(onClick = onAddToPlaylist) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = "Add Track to Playlist",
                        tint = Color.White
                    )
                }
            }
        }

        // E2. Smooth Sliding center panel (Artwork, Lyrics, Equalizer)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (activePanel) {
                "Artwork" -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        TrackArtworkPattern(
                            track = track,
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .size(290.dp)
                                .shadow(24.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { onToggle() }
                        )

                        // Sleep countdown live presentation
                        if (sleepTimerRemaining != null) {
                            val mins = (sleepTimerRemaining / 1000) / 60
                            val secs = (sleepTimerRemaining / 1000) % 60
                            Spacer(modifier = Modifier.height(20.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Sleep countdown",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = String.format("Auto-pause in %d:%02d", mins, secs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
                "Lyrics" -> {
                    LyricsPane(
                        lyrics = track.lyrics,
                        currentPositionMs = currentPosition,
                        accentColor = Color(track.colorStart),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                "Equalizer FX" -> {
                    EqualizerControlPanel(
                        settings = equalizerSettings,
                        accentColor = Color(track.colorStart),
                        onTweakBass = onTweakBass,
                        onTweakSpatial = onTweakSpatial,
                        onTweakClarify = onTweakClarify
                    )
                }
                "Tag Studio" -> {
                    com.example.ui.components.TagStudioPane(
                        track = track,
                        tagUpdateStatus = tagUpdateStatus,
                        onSaveTags = { text, uri -> onSaveTags(track.id, text, uri) },
                        onClearStatus = onClearTagStatus,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // E3. Panel Selection Segment Tabs
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .padding(4.dp)
        ) {
            val panels = listOf("Artwork", "Lyrics", "Equalizer FX", "Tag Studio")
            panels.forEach { panel ->
                val active = activePanel == panel
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(track.colorStart) else Color.Transparent)
                        .clickable { activePanel = panel }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = panel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) Color.White else TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // E4. Song meta indicators
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onFavToggle) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorit",
                        tint = if (isFavorite) Color.Red else Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // E5. Absolute Pro Seeker Bar
            Slider(
                value = progress,
                onValueChange = { targetProgress ->
                    val targetMs = (targetProgress * track.durationMs).toLong()
                    onSeek(targetMs)
                },
                colors = SliderDefaults.colors(
                    activeTrackColor = Color(track.colorStart),
                    inactiveTrackColor = DividerColor,
                    thumbColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(elapsedTimeStr, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(totalTimeStr, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        // E6. Neon active Visualizer canvas
        AudioWaveVisualizer(
            spectrum = amplitudeSpectrum,
            accentColor = Color(track.colorStart),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 24.dp, vertical = 4.dp)
        )

        // E7. Main Playback HUD Controls Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffleToggle) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "ShuffleToggle",
                    tint = if (isShuffle) Color(track.colorStart) else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(onClick = onPrev) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Prev",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Big FAB-style circular Play Button
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "PlayToggle",
                    tint = MidnightBackground,
                    modifier = Modifier.size(42.dp)
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = onRepeatToggle) {
                Icon(
                    Icons.Default.Refresh, // Stably compiles and serves repeat-one function
                    contentDescription = "RepeatToggle",
                    tint = if (isRepeatOne) Color(track.colorStart) else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun EqualizerControlPanel(
    settings: EqualizerSettings,
    accentColor: Color,
    onTweakBass: (Float) -> Unit,
    onTweakSpatial: (Float) -> Unit,
    onTweakClarify: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(CardSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            Icon(Icons.Default.Equalizer, contentDescription = "FX", tint = accentColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pro-VLC Tuning Deck", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
        }

        Text("Sculpt your morning frequency output dynamically.", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(bottom = 20.dp))

        // Slide 1: Bass Boost
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Sub-Bass Core Pulse", color = Color.White, fontWeight = FontWeight.Medium)
                Text("${(settings.bassBoost * 100).toInt()}%", color = accentColor, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.bassBoost,
                onValueChange = onTweakBass,
                colors = SliderDefaults.colors(activeTrackColor = accentColor, thumbColor = Color.White)
            )
        }

        // Slide 2: Spatial Audio
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Spatial Stage Width (3D)", color = Color.White, fontWeight = FontWeight.Medium)
                Text("${(settings.spatialAudio * 100).toInt()}%", color = accentColor, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.spatialAudio,
                onValueChange = onTweakSpatial,
                colors = SliderDefaults.colors(activeTrackColor = accentColor, thumbColor = Color.White)
            )
        }

        // Slide 3: Vocal Boost
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Vocal Resonance Clarity", color = Color.White, fontWeight = FontWeight.Medium)
                Text("${(settings.vocalClarity * 100).toInt()}%", color = accentColor, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.vocalClarity,
                onValueChange = onTweakClarify,
                colors = SliderDefaults.colors(activeTrackColor = accentColor, thumbColor = Color.White)
            )
        }
    }
}

// ======================== NEW SUB-SYSTEM COMPOSABLES ========================

@Composable
fun SleekBottomNavigationBar(
    selectedTab: String,
    onTabSelected: (String) -> Unit
) {
    val items = listOf("Library", "Playlists", "Equalizer", "Storage")
    val icons = mapOf(
        "Library" to Icons.Default.MusicNote,
        "Playlists" to Icons.Default.QueueMusic,
        "Equalizer" to Icons.Default.Equalizer,
        "Storage" to Icons.Default.FolderOpen
    )
    
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { tab ->
                val isSelected = selectedTab == tab
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTabSelected(tab) }
                        .padding(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icons[tab] ?: Icons.Default.MusicNote,
                            contentDescription = tab,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tab,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun SleekOnboardingWizard(
    onComplete: () -> Unit,
    onActivateScan: () -> Unit
) {
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = 4

    Dialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = {}
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Page indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        repeat(totalPages) { pageIndex ->
                            val active = pageIndex == currentPage
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(if (active) 20.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                    )
                            )
                        }
                    }

                    // Page main content box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentAlignment = Alignment.Center
                    ) {
                        when (currentPage) {
                            0 -> OnboardingPageContent(
                                icon = Icons.Default.MusicNote,
                                title = "Sleek Ambient Player",
                                description = "Welcome to your high-fidelity, distraction-free sanctuary for focus, relaxation, and music tracks."
                            )
                            1 -> OnboardingPageContent(
                                icon = Icons.Default.Timelapse,
                                title = "In-App Sleep Timers",
                                description = "Listen peacefully into the night. Configure our smart Sleep Timer to gradually fade down output and stop playback automatically when you fall asleep."
                            )
                            2 -> OnboardingPageContent(
                                icon = Icons.Default.Equalizer,
                                title = "Pro DSP Tuning Deck",
                                description = "Sculpt your frequencies with our professional 5-Band Equalizer, real-time Virtualizer stage depth, dynamic Sub-Bass core pulse, and sound reverberation controls."
                            )
                            3 -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(vertical = 12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = "Storage",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Unleash Local Mediums",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Scan music folders from your internal directories, high-speed SD Cards, or external OTG USB storage hardware safely and reliably.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        onActivateScan()
                                        onComplete()
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.SettingsSuggest, "Scan")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Activate Media Storage Scan", fontWeight = FontWeight.Bold)
                                }
                             }
                        }
                    }

                    // Navigation footer row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentPage < totalPages - 1) {
                            TextButton(onClick = onComplete) {
                                Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(48.dp))
                        }

                        if (currentPage < totalPages - 1) {
                            Button(
                                onClick = { currentPage++ },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Continue")
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Default.ArrowForward, "Next", modifier = Modifier.size(14.dp))
                            }
                        } else {
                            TextButton(onClick = onComplete) {
                                Text("Finish Setup", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingPageContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(54.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
            lineHeight = 22.sp
        )
    }
}

@Composable
fun DeviceStoragePage(
    isScanning: Boolean,
    statusText: String,
    onTriggerScan: () -> Unit,
    onResetOnboarding: () -> Unit,
    onImportFolder: (android.net.Uri) -> Unit,
    onImportFiles: (List<android.net.Uri>) -> Unit,
    onClearImported: () -> Unit,
    importedTracksCount: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val folderLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            onImportFolder(uri)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<android.net.Uri>? ->
        if (!uris.isNullOrEmpty()) {
            onImportFiles(uris)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "HARDWARE STORAGE SCANNERS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // System scanner card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cached,
                        contentDescription = "System scan",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Auto System Scanner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                     text = "Triggers a full underlying scan of your device's Media Library to index all local tracks automatically.",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (isScanning) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontStyle = FontStyle.Italic
                        )
                    }
                } else {
                    Button(
                        onClick = onTriggerScan,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.SettingsSuggest, "Scan")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Instant System Scan", fontWeight = FontWeight.Bold)
                    }
                    if (statusText != "Storage scanner idle") {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Text(
            text = "ADVANCED FILE & HARDWARE DRIVES",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // Custom Pickers Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = "SAF Pickers",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Physical Drive Integrator",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                     text = "Select custom musical resources directly from connected hardware storage locations, including expandable external SD Cards and USB OTG Flash Drives.",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (importedTracksCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, "Imported", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Currently loaded: $importedTracksCount virtual tracks",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Folder, "Folder")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Folder", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { fileLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Audiotrack, "Files")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Songs", fontWeight = FontWeight.SemiBold)
                    }
                }

                if (importedTracksCount > 0) {
                    OutlinedButton(
                        onClick = onClearImported,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, "Clear")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Imported Tracks", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Text(
            text = "HARDWARE DETECTED CHANNELS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        DirectoryNodeCard(
            title = "External USB Drive (OTG)",
            path = "Accessible via 'Pick Folder' → USB Hub",
            icon = Icons.Default.Usb,
            connected = true
        )

        DirectoryNodeCard(
            title = "MicroSD Expansion Storage",
            path = "Accessible via 'Pick Folder' → SD Card",
            icon = Icons.Default.SdCard,
            connected = true
        )

        DirectoryNodeCard(
            title = "Internal Device Directories",
            path = "Accessible via 'Pick Songs' or 'Pick Folder'",
            icon = Icons.Default.Folder,
            connected = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onResetOnboarding,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.HelpOutline, "Tutorial")
            Spacer(modifier = Modifier.width(6.dp))
            Text("Re-watch Tutorial Walkthrough", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DirectoryNodeCard(
    title: String,
    path: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    connected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, title, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AdvancedEqualizerPage(
    settings: EqualizerSettings,
    spectrum: FloatArray,
    onPresetSelected: (String) -> Unit,
    onBandUpdated: (Int, Float) -> Unit,
    onBassTweak: (Float) -> Unit,
    onSpatialTweak: (Float) -> Unit,
    onReverbTweak: (Float) -> Unit,
    onVocalTweak: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SOUND EFFECT PRESETS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf("Flat", "Rock", "Pop", "Classical", "Bass Boost", "Custom")
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { preset ->
                    val isSelected = settings.preset == preset
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPresetSelected(preset) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Text(
            text = "5-BAND EQUALIZER TUNER",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        val frequencies = listOf("60 Hz", "230 Hz", "910 Hz", "4 kHz", "14 kHz")
        frequencies.forEachIndexed { index, freqName ->
            val bandVal = settings.bands.getOrNull(index) ?: 0.5f
            val dbVal = ((bandVal - 0.5f) * 24).toInt()
            val dbText = if (dbVal > 0) "+$dbVal dB" else "$dbVal dB"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = freqName,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(60.dp)
                )
                Slider(
                    value = bandVal,
                    onValueChange = { onBandUpdated(index, it) },
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        thumbColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dbText,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.End
                )
            }
        }

        Text(
            text = "PRO SOUND STAGE FX",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Sub-Bass Core Pulse", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text("${(settings.bassBoost * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.bassBoost,
                onValueChange = onBassTweak,
                colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary, thumbColor = MaterialTheme.colorScheme.primary)
            )
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Spatial Stage Width (3D Virtualizer)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text("${(settings.spatialAudio * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.spatialAudio,
                onValueChange = onSpatialTweak,
                colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary, thumbColor = MaterialTheme.colorScheme.primary)
            )
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Reverb Environment Intensity", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text("${(settings.reverbAmount * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.reverbAmount,
                onValueChange = onReverbTweak,
                colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary, thumbColor = MaterialTheme.colorScheme.primary)
            )
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Vocal Resonance Clarity", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text("${(settings.vocalClarity * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = settings.vocalClarity,
                onValueChange = onVocalTweak,
                colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary, thumbColor = MaterialTheme.colorScheme.primary)
            )
        }
    }
}


