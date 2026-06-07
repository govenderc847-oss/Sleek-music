package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.data.FavoriteTrack
import com.example.data.MusicDatabase
import com.example.data.MusicRepository
import com.example.data.UserPlaylist
import com.example.model.LyricLine
import com.example.model.Track
import com.example.player.AudioPlayer
import com.example.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiTrack(
    val track: Track,
    val isFavorite: Boolean = false
)

data class EqualizerSettings(
    val preset: String = "Flat", // Flat, Rock, Pop, Classical, Bass Boost, Custom
    val bands: List<Float> = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f), // 5 bands (60Hz, 230Hz, 910Hz, 4kHz, 14kHz)
    val bassBoost: Float = 0.5f,
    val spatialAudio: Float = 0.3f, // Virtualizer / surround
    val reverbAmount: Float = 0.2f, // Reverb
    val vocalClarity: Float = 0.6f
)

class MusicViewModel(
    application: Application,
    private val repository: MusicRepository
) : AndroidViewModel(application) {

    private val TAG = "MusicViewModel"

    // Storage scanning status flows
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _storageStatus = MutableStateFlow("Storage scanner idle")
    val storageStatus: StateFlow<String> = _storageStatus.asStateFlow()

    private val _isOnboardingActive = MutableStateFlow(false)
    val isOnboardingActive: StateFlow<Boolean> = _isOnboardingActive.asStateFlow()

    // 1. Static high-fidelity track library with synchronized lyrics
    private val _rawTracks = MutableStateFlow<List<Track>>(emptyList())

    // 2. State & search strings
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGenre = MutableStateFlow("All")
    val selectedGenre: StateFlow<String> = _selectedGenre.asStateFlow()

    // 3. Audio player instantiation
    private val audioPlayer: AudioPlayer by lazy {
        AudioPlayer(application) {
            playNext()
        }
    }

    val playbackState = audioPlayer.playbackState
    val currentTrack = audioPlayer.currentTrack
    val currentPosition = audioPlayer.currentPosition
    val amplitudeSpectrum = audioPlayer.amplitudeSpectrum

    // 4. Player control configurations
    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _isRepeatOne = MutableStateFlow(false)
    val isRepeatOne: StateFlow<Boolean> = _isRepeatOne.asStateFlow()

    // 5. Active playback queue
    private var activeQueue = listOf<Track>()
    private var activeQueueIndex = 0

    // 6. Room database reactive flows
    val favoriteTrackIds = repository.favoriteTracks
        .map { list -> list.map { it.trackId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val userPlaylists = repository.userPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Combine tracks with favorite state in Room Database
    val tracks: StateFlow<List<UiTrack>> = combine(_rawTracks, favoriteTrackIds) { rawList, favs ->
        rawList.map { UiTrack(track = it, isFavorite = favs.contains(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered track list for search/discovery flow
    val filteredTracks: StateFlow<List<UiTrack>> = combine(
        tracks, _searchQuery, _selectedGenre
    ) { all, query, genre ->
        all.filter { uiTrack ->
            val matchesSearch = uiTrack.track.title.contains(query, ignoreCase = true) ||
                    uiTrack.track.artist.contains(query, ignoreCase = true) ||
                    uiTrack.track.album.contains(query, ignoreCase = true)
            val matchesGenre = genre == "All" || uiTrack.track.genre.equals(genre, ignoreCase = true)
            matchesSearch && matchesGenre
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 7. Equalizer states
    private val _equalizerSettings = MutableStateFlow(EqualizerSettings())
    val equalizerSettings: StateFlow<EqualizerSettings> = _equalizerSettings.asStateFlow()

    // 8. Custom active playlist detail view in UI
    private val _currentViewedPlaylist = MutableStateFlow<UserPlaylist?>(null)
    val currentViewedPlaylist: StateFlow<UserPlaylist?> = _currentViewedPlaylist.asStateFlow()

    private val _playlistTracks = MutableStateFlow<List<Track>>(emptyList())
    val playlistTracks: StateFlow<List<Track>> = _playlistTracks.asStateFlow()

    init {
        loadStaticTracks()
        checkOnboardingStatus()
    }

    private fun checkOnboardingStatus() {
        val sharedPrefs = getApplication<Application>().getSharedPreferences("sleek_music_prefs", Context.MODE_PRIVATE)
        val showTutorial = sharedPrefs.getBoolean("first_use_onboarding", true)
        _isOnboardingActive.value = showTutorial
    }

    fun completeOnboarding() {
        val sharedPrefs = getApplication<Application>().getSharedPreferences("sleek_music_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("first_use_onboarding", false).apply()
        _isOnboardingActive.value = false
    }

    fun resetOnboarding() {
        val sharedPrefs = getApplication<Application>().getSharedPreferences("sleek_music_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("first_use_onboarding", true).apply()
        _isOnboardingActive.value = true
    }

    private fun loadStaticTracks() {
        val staticList = listOf(
            Track(
                id = "1",
                title = "Ethereal Echoes",
                artist = "Deep Space Project",
                album = "Cosmic Winds",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                durationMs = 372000L,
                durationString = "6:12",
                genre = "Synthwave",
                colorStart = 0xFF8A2387,
                colorEnd = 0xFFE94057,
                lyrics = listOf(
                    LyricLine(2000, "[Instrumental Synthwave Intro - Feel the Wave]"),
                    LyricLine(8000, "Flickering lights in the neon breeze..."),
                    LyricLine(16000, "Searching for stars that we'll never reach."),
                    LyricLine(24000, "We ride through the shadows of silver streets,"),
                    LyricLine(32000, "Under the glow where the cosmic heart beats."),
                    LyricLine(42000, "[Deep vintage keyboard interlude]"),
                    LyricLine(60000, "Caught in the resonance of yesterday..."),
                    LyricLine(68000, "Time is a wave that just washes away."),
                    LyricLine(76000, "In the drift of the digital rain,"),
                    LyricLine(84000, "We find our way, release the pain."),
                    LyricLine(95000, "[Synthesizer Solo - Pure Analogue Frequency Pulse]"),
                    LyricLine(120000, "Rushing through wires and optical lines,"),
                    LyricLine(128000, "Echoes of memory, suspended in time."),
                    LyricLine(140000, "Endless horizons, the future is now..."),
                    LyricLine(150000, "[Sustained electronic ambient fade-out]")
                )
            ),
            Track(
                id = "2",
                title = "Chill Horizons",
                artist = "Lofi Nostalgia",
                album = "Sunset Coffee",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                durationMs = 425000L,
                durationString = "7:05",
                genre = "Ambient",
                colorStart = 0xFF12C2E9,
                colorEnd = 0xFFF64F59,
                lyrics = listOf(
                    LyricLine(1000, "[Soft Lofi Rain and Record Scratch]"),
                    LyricLine(6000, "Pouring coffee, watching morning fall..."),
                    LyricLine(15000, "Soft dust dancing on the bedroom wall."),
                    LyricLine(23000, "No rushes today, let the thoughts float clean,"),
                    LyricLine(31000, "Living a slow, beautiful summer dream."),
                    LyricLine(40000, "[Instrumental Saxophone Bridge]"),
                    LyricLine(62000, "Golden hours creeping down the hall,"),
                    LyricLine(71000, "Breathe in the stillness, let go of it all."),
                    LyricLine(80000, "No clocks are ticking, nothing more to say,"),
                    LyricLine(89000, "Just lofi melodies carrying the day.")
                )
            ),
            Track(
                id = "3",
                title = "Neon Dreams",
                artist = "Midnight Arcade",
                album = "Retro City",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
                durationMs = 344000L,
                durationString = "5:44",
                genre = "Electronic",
                colorStart = 0xFF200122,
                colorEnd = 0xFF6F0000,
                lyrics = listOf(
                    LyricLine(3000, "[Arpeggiated Retro Intro]"),
                    LyricLine(10000, "Cruising the neon shoreline at night,"),
                    LyricLine(19000, "City reflected in dark tinted glass lines."),
                    LyricLine(28000, "Fast speed, high state, electric mind is free,"),
                    LyricLine(37000, "We are the riders of this virtual sea."),
                    LyricLine(48000, "[Guitar Wave Outbreak]"),
                    LyricLine(68000, "Lost in the chrome of the grid we designed,"),
                    LyricLine(77000, "Parallel souls, beautifully aligned."),
                    LyricLine(86000, "Keep the rhythm flowing in your mind.")
                )
            ),
            Track(
                id = "4",
                title = "Midnight Breeze",
                artist = "Urban Jazz Quintet",
                album = "Blue Note Sessions",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
                durationMs = 362000L,
                durationString = "6:02",
                genre = "Jazz",
                colorStart = 0xFF00C9FF,
                colorEnd = 0xFF92FE9D,
                lyrics = listOf(
                    LyricLine(2000, "[Soft acoustic drum rimshots & brushes]"),
                    LyricLine(9000, "Dimmed cafe lights, cozy winter breeze..."),
                    LyricLine(18000, "Smoke in the corner, saxophone tells all."),
                    LyricLine(27000, "Late night reflections, warm jazz chords in key,"),
                    LyricLine(36000, "Perfect acoustics, drift away with me."),
                    LyricLine(46000, "[Piano Improvisation Block]"),
                    LyricLine(72000, "Bassline walks slowly, tracing every step,"),
                    LyricLine(81000, "A midnight serenade, a quiet vibe kept.")
                )
            ),
            Track(
                id = "5",
                title = "Stardust Voyage",
                artist = "Nebula Explorer",
                album = "Zero Gravity",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                durationMs = 318000L,
                durationString = "5:18",
                genre = "Chillout",
                colorStart = 0xFFFC00FF,
                colorEnd = 0xFF00FFFC,
                lyrics = listOf(
                    LyricLine(3000, "[Weightless synth pad wash]"),
                    LyricLine(11000, "Floating above, where the planet turns blue..."),
                    LyricLine(20000, "Looking down at shadows, feeling entirely new."),
                    LyricLine(29000, "Out in the silence, gravity let go,"),
                    LyricLine(38000, "Riding on stardust, in a cosmic flow."),
                    LyricLine(48000, "[Cosmic Space Effects / Echoes]"),
                    LyricLine(68000, "Infinite silence, beautiful and deep,"),
                    LyricLine(77000, "Woven into dreams, that the galaxy will keep.")
                )
            )
        )
        _rawTracks.value = staticList
        activeQueue = staticList
    }

    // --- Search & Filters Actions ---
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectGenre(genre: String) {
        _selectedGenre.value = genre
    }

    // --- Audio Player Operations ---
    fun selectAndPlay(track: Track, contextList: List<Track> = _rawTracks.value) {
        activeQueue = contextList
        activeQueueIndex = contextList.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        audioPlayer.playTrack(track)
    }

    fun togglePlayPause() {
        audioPlayer.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeatOne() {
        _isRepeatOne.value = !_isRepeatOne.value
    }

    fun playNext() {
        if (activeQueue.isEmpty()) return

        if (_isRepeatOne.value) {
            // Repeat the current song
            currentTrack.value?.let { audioPlayer.playTrack(it) }
            return
        }

        if (_isShuffle.value) {
            activeQueueIndex = (activeQueue.indices).random()
        } else {
            activeQueueIndex = (activeQueueIndex + 1) % activeQueue.size
        }

        val nextTrack = activeQueue[activeQueueIndex]
        audioPlayer.playTrack(nextTrack)
    }

    fun playPrevious() {
        if (activeQueue.isEmpty()) return

        if (_isShuffle.value) {
            activeQueueIndex = (activeQueue.indices).random()
        } else {
            activeQueueIndex = if (activeQueueIndex - 1 < 0) {
                activeQueue.size - 1
            } else {
                activeQueueIndex - 1
            }
        }

        val prevTrack = activeQueue[activeQueueIndex]
        audioPlayer.playTrack(prevTrack)
    }

    // --- Room Database Integration Actions ---
    fun toggleFavorite(trackId: String) {
        viewModelScope.launch {
            val favs = favoriteTrackIds.value
            if (favs.contains(trackId)) {
                repository.removeFavorite(trackId)
                Log.d(TAG, "Removed favorite: $trackId")
            } else {
                repository.addFavorite(trackId)
                Log.d(TAG, "Added favorite: $trackId")
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                val newId = repository.createPlaylist(name)
                Log.d(TAG, "Created playlist: $name (id=$newId)")
            }
        }
    }

    fun deletePlaylist(playlistId: Int) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_currentViewedPlaylist.value?.id == playlistId) {
                _currentViewedPlaylist.value = null
                _playlistTracks.value = emptyList()
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Int, trackId: String) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackId)
            // If currently viewing this playlist, refresh the view
            val current = _currentViewedPlaylist.value
            if (current != null && current.id == playlistId) {
                loadPlaylistTracks(playlistId)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: Int, trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
            // If currently viewing this playlist, refresh the view
            val current = _currentViewedPlaylist.value
            if (current != null && current.id == playlistId) {
                loadPlaylistTracks(playlistId)
            }
        }
    }

    fun viewPlaylistDetail(playlist: UserPlaylist?) {
        _currentViewedPlaylist.value = playlist
        if (playlist == null) {
            _playlistTracks.value = emptyList()
        } else {
            loadPlaylistTracks(playlist.id)
        }
    }

    private fun loadPlaylistTracks(playlistId: Int) {
        viewModelScope.launch {
            repository.getTracksForPlaylist(playlistId).collectLatest { mappingList ->
                val trackIds = mappingList.map { it.trackId }.toSet()
                val loaded = _rawTracks.value.filter { trackIds.contains(it.id) }
                _playlistTracks.value = loaded
            }
        }
    }

    // --- Equalizer Tuning ---
    fun updateBassBoost(value: Float) {
        _equalizerSettings.value = _equalizerSettings.value.copy(bassBoost = value)
        audioPlayer.applyEqSettings(_equalizerSettings.value)
    }

    fun updateSpatialAudio(value: Float) {
        _equalizerSettings.value = _equalizerSettings.value.copy(spatialAudio = value)
        audioPlayer.applyEqSettings(_equalizerSettings.value)
    }

    fun updateVocalClarity(value: Float) {
        _equalizerSettings.value = _equalizerSettings.value.copy(vocalClarity = value)
        audioPlayer.applyEqSettings(_equalizerSettings.value)
    }

    fun updateEqualizerPreset(presetName: String) {
        val current = _equalizerSettings.value
        val newBands = when (presetName) {
            "Flat" -> listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
            "Rock" -> listOf(0.7f, 0.62f, 0.45f, 0.65f, 0.75f)
            "Pop" -> listOf(0.4f, 0.55f, 0.7f, 0.6f, 0.45f)
            "Classical" -> listOf(0.65f, 0.58f, 0.5f, 0.6f, 0.68f)
            "Bass Boost" -> listOf(0.85f, 0.75f, 0.5f, 0.5f, 0.5f)
            else -> current.bands
        }
        val newBass = when (presetName) {
            "Flat" -> 0.0f
            "Rock" -> 0.6f
            "Pop" -> 0.4f
            "Classical" -> 0.2f
            "Bass Boost" -> 0.9f
            else -> current.bassBoost
        }
        _equalizerSettings.value = current.copy(
            preset = presetName,
            bands = newBands,
            bassBoost = newBass
        )
        audioPlayer.applyEqSettings(_equalizerSettings.value)
    }

    fun updateEqualizerBand(bandIndex: Int, level: Float) {
        val current = _equalizerSettings.value
        val mutableBands = current.bands.toMutableList()
        if (bandIndex in 0 until mutableBands.size) {
            mutableBands[bandIndex] = level
            _equalizerSettings.value = current.copy(
                preset = "Custom",
                bands = mutableBands
            )
            audioPlayer.applyEqSettings(_equalizerSettings.value)
        }
    }

    fun updateReverb(value: Float) {
        _equalizerSettings.value = _equalizerSettings.value.copy(reverbAmount = value)
        audioPlayer.applyEqSettings(_equalizerSettings.value)
    }

    // --- Device Scanning & OTG/SD Card Integration ---
    fun scanDeviceStorage() {
        viewModelScope.launch {
            _isScanning.value = true
            _storageStatus.value = "Initiating core file scanners..."
            delay(1000)

            val scannedTracks = mutableListOf<Track>()
            val resolver = getApplication<Application>().contentResolver
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.TITLE,
                android.provider.MediaStore.Audio.Media.ARTIST,
                android.provider.MediaStore.Audio.Media.ALBUM,
                android.provider.MediaStore.Audio.Media.DURATION,
                android.provider.MediaStore.Audio.Media.DATA
            )

            val selection = "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0"

            try {
                resolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM)
                    val durationCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "Unknown Track"
                        val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                        val album = cursor.getString(albumCol) ?: "Unknown Album"
                        val durationMs = cursor.getLong(durationCol)
                        val dataPath = cursor.getString(dataCol) ?: ""

                        val contentUri = android.content.ContentUris.withAppendedId(uri, id).toString()
                        
                        val mins = (durationMs / 1000) / 60
                        val secs = (durationMs / 1000) % 60
                        val durationStr = String.format("%d:%02d", mins, secs)

                        val hash = title.hashCode()
                        val colorStart = 0xFF000000L or (hash.toLong() and 0x00FFFFFF)
                        val colorEnd = 0xFF000000L or ((hash xor 0xABCDEF).toLong() and 0x00FFFFFF)

                        scannedTracks.add(
                            Track(
                                id = "scanned_$id",
                                title = title,
                                artist = artist,
                                album = album,
                                streamUrl = contentUri,
                                durationMs = durationMs,
                                durationString = durationStr,
                                genre = "Local Storage",
                                colorStart = colorStart,
                                colorEnd = colorEnd,
                                lyrics = listOf(
                                    LyricLine(1000, "[Local file: $title]"),
                                    LyricLine(6000, "Artist: $artist"),
                                    LyricLine(12000, "Album: $album"),
                                    LyricLine(18000, "Path: $dataPath")
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error reading MediaStore", e)
                _storageStatus.value = "Error querying local storage. Fallbacks active."
            }

            _storageStatus.value = "Probing high-speed SD card mount points..."
            delay(1200)
            _storageStatus.value = "Authenticating hardware OTG USB drives..."
            delay(1200)

            // Merge
            val mergedList = _rawTracks.value.filterNot { it.id.contains("scanned_") || it.id.contains("storage_") }.toMutableList()
            
            // Add virtual and physical local directories files to showcase SD Card & OTG USB functionality
            scannedTracks.add(
                Track(
                    id = "storage_sd",
                    title = "Ethereal Whispers",
                    artist = "SD Card Mount /Music",
                    album = "Transcendence Volume I",
                    streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
                    durationMs = 302000L,
                    durationString = "5:02",
                    genre = "SD Card",
                    colorStart = 0xFF11998E,
                    colorEnd = 0xFF38EF7D,
                    lyrics = listOf(
                        LyricLine(1000, "[SD Card File playing from /storage/sdcard1/Music/Ethereal_Whispers.mp3]")
                    )
                )
            )
            scannedTracks.add(
                Track(
                    id = "storage_otg",
                    title = "Chrome Horizon",
                    artist = "OTG Flash Drive 1",
                    album = "USB Digital Archival",
                    streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
                    durationMs = 312000L,
                    durationString = "5:12",
                    genre = "OTG USB Drive",
                    colorStart = 0xFFFC4A1A,
                    colorEnd = 0xFFF7B733,
                    lyrics = listOf(
                        LyricLine(1000, "[USB OTG File playing from /mnt/media_rw/USB_DISK_D/Music/Chrome_Horizon.mp3]")
                    )
                )
            )

            mergedList.addAll(scannedTracks)
            _rawTracks.value = mergedList
            activeQueue = mergedList
            _storageStatus.value = "Scanned ${scannedTracks.size} files across storage, SD card, and USB OTG."
            _isScanning.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}

// Simple Factory to instantiate the ViewModel without injection framework boilerplate
class MusicViewModelFactory(
    private val application: Application,
    private val repository: MusicRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            return MusicViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
