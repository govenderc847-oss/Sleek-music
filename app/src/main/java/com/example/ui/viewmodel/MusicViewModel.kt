package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.withContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import androidx.documentfile.provider.DocumentFile

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

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    // Storage scanning status flows
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _storageStatus = MutableStateFlow("Storage scanner idle")
    val storageStatus: StateFlow<String> = _storageStatus.asStateFlow()

    private val _isOnboardingActive = MutableStateFlow(false)
    val isOnboardingActive: StateFlow<Boolean> = _isOnboardingActive.asStateFlow()

    // Sleep Timer status flows
    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null)
    val sleepTimerRemaining: StateFlow<Long?> = _sleepTimerRemaining.asStateFlow()

    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemaining.value = null
            return
        }
        val targetMs = minutes * 60 * 1000L
        _sleepTimerRemaining.value = targetMs
        
        sleepTimerJob = viewModelScope.launch(Dispatchers.Default) {
            var timeRemaining = targetMs
            while (timeRemaining > 0) {
                delay(1000)
                timeRemaining -= 1000
                _sleepTimerRemaining.value = timeRemaining
            }
            _sleepTimerRemaining.value = null
            viewModelScope.launch {
                audioPlayer.pause()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
    }

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
        if (!_isOnboardingActive.value) {
            scanDeviceStorage()
        }
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
        _rawTracks.value = emptyList()
        activeQueue = emptyList()
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
    private fun saveCustomTracksToPrefs(context: Context, tracksList: List<Track>) {
        try {
            val sharedPrefs = context.getSharedPreferences("sleek_music_prefs", Context.MODE_PRIVATE)
            val json = moshi.adapter<List<Track>>(Types.newParameterizedType(List::class.java, Track::class.java)).toJson(tracksList)
            sharedPrefs.edit().putString("custom_imported_tracks", json).apply()
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Error saving imported tracks", e)
        }
    }

    fun loadCustomTracksFromPrefs(context: Context): List<Track> {
        try {
            val sharedPrefs = context.getSharedPreferences("sleek_music_prefs", Context.MODE_PRIVATE)
            val json = sharedPrefs.getString("custom_imported_tracks", null) ?: return emptyList()
            return moshi.adapter<List<Track>>(Types.newParameterizedType(List::class.java, Track::class.java)).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Error loading imported tracks", e)
            return emptyList()
        }
    }

    fun refreshTrackLibrary(context: Context) {
        val storageTracks = mutableListOf<Track>()
        
        // 1. Get standard MediaStore scanned tracks
        val mediaStoreTracks = queryMediaStoreTracks(context)
        storageTracks.addAll(mediaStoreTracks)

        // 2. Get custom SAF-imported files
        val customTracks = loadCustomTracksFromPrefs(context)
        storageTracks.addAll(customTracks.filter { custom -> mediaStoreTracks.none { it.streamUrl == custom.streamUrl } })

        // 3. Clear existing mediaStore scanned tracks in _rawTracks, then re-merge
        val baseTracks = _rawTracks.value.filter { 
            !it.id.contains("scanned_") && !it.id.contains("storage_") && !it.id.contains("saf_")
        }.toMutableList()

        baseTracks.addAll(storageTracks)
        _rawTracks.value = baseTracks
        activeQueue = baseTracks
    }

    private fun queryMediaStoreTracks(context: Context): List<Track> {
        val scannedTracks = mutableListOf<Track>()
        val resolver = context.contentResolver
        val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.DATA,
            android.provider.MediaStore.Audio.Media.ALBUM_ID
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
                val albumIdCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val durationMs = cursor.getLong(durationCol)
                    val dataPath = cursor.getString(dataCol) ?: ""
                    val albumId = cursor.getLong(albumIdCol)

                    val contentUri = android.content.ContentUris.withAppendedId(uri, id).toString()
                    val coverUriString = android.content.ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()

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
                            coverUri = coverUriString,
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
        }
        return scannedTracks
    }

    fun importTracksFromFolderUri(context: Context, treeUri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Take persistable permission
                val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.isDirectory) {
                    val scannedList = mutableListOf<Track>()
                    _isScanning.value = true
                    _storageStatus.value = "Scanning recursive folder tree..."

                    scanDirectoryRecursive(context, docFile, scannedList)

                    _storageStatus.value = "Registering ${scannedList.size} new tracks from storage..."
                    val currentCustom = loadCustomTracksFromPrefs(context).toMutableList()
                    val newUnique = scannedList.filter { fileTrack -> currentCustom.none { it.streamUrl == fileTrack.streamUrl } }
                    currentCustom.addAll(newUnique)
                    saveCustomTracksToPrefs(context, currentCustom)

                    withContext(Dispatchers.Main) {
                        refreshTrackLibrary(context)
                        _storageStatus.value = "Imported ${newUnique.size} tracks from SD Card/USB OTG!"
                        _isScanning.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to import from folder URI", e)
                withContext(Dispatchers.Main) {
                    _storageStatus.value = "Failed to import folder tree."
                    _isScanning.value = false
                }
            }
        }
    }

    private fun scanDirectoryRecursive(
        context: Context,
        directory: DocumentFile,
        scannedList: MutableList<Track>
    ) {
        val files = directory.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursive(context, file, scannedList)
            } else if (file.isFile) {
                val name = file.name ?: continue
                if (name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".m4a", true) || name.endsWith(".ogg", true)) {
                    val track = buildTrackFromDocumentFile(context, file)
                    if (track != null) {
                        scannedList.add(track)
                    }
                }
            }
        }
    }

    private fun buildTrackFromDocumentFile(
        context: Context,
        file: DocumentFile
    ): Track? {
        var retriever: android.media.MediaMetadataRetriever? = null
        try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, file.uri)

            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name?.substringBeforeLast('.') ?: "Unknown Track"
            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 180000L

            val mins = (durationMs / 1000) / 60
            val secs = (durationMs / 1000) % 60
            val durationString = String.format("%d:%02d", mins, secs)

            val hash = title.hashCode()
            val colorStart = 0xFF000000L or (hash.toLong() and 0x00FFFFFF)
            val colorEnd = 0xFF000000L or ((hash xor 0xABCDEF).toLong() and 0x00FFFFFF)

            val trackId = "saf_${System.currentTimeMillis()}_${hash}"
            return Track(
                id = trackId,
                title = title,
                artist = artist,
                album = album,
                streamUrl = file.uri.toString(),
                durationMs = durationMs,
                durationString = durationString,
                genre = "Local Storage",
                colorStart = colorStart,
                colorEnd = colorEnd,
                coverUri = file.uri.toString()
            )
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Error parsing metadata for DocumentFile ${file.name}", e)
            return null
        } finally {
            try { retriever?.release() } catch (ignored: Exception) {}
        }
    }

    fun importTracksFromUris(context: Context, uris: List<android.net.Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isScanning.value = true
                _storageStatus.value = "Importing selected files..."
                val scannedList = mutableListOf<Track>()

                for (uri in uris) {
                    try {
                        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: Exception) {
                        Log.w("MusicViewModel", "Could not take persistable Uri permission", e)
                    }

                    val doc = DocumentFile.fromSingleUri(context, uri) ?: continue
                    val track = buildTrackFromDocumentFile(context, doc)
                    if (track != null) {
                        scannedList.add(track)
                    }
                }

                val currentCustom = loadCustomTracksFromPrefs(context).toMutableList()
                val newUnique = scannedList.filter { fileTrack -> currentCustom.none { it.streamUrl == fileTrack.streamUrl } }
                currentCustom.addAll(newUnique)
                saveCustomTracksToPrefs(context, currentCustom)

                withContext(Dispatchers.Main) {
                    refreshTrackLibrary(context)
                    _storageStatus.value = "Imported ${newUnique.size} selected songs!"
                    _isScanning.value = false
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to import selected files", e)
                withContext(Dispatchers.Main) {
                    _storageStatus.value = "Failed to import selected songs."
                    _isScanning.value = false
                }
            }
        }
    }

    fun clearCustomImportedTracks(context: Context) {
        saveCustomTracksToPrefs(context, emptyList())
        refreshTrackLibrary(context)
        _storageStatus.value = "Cleared all imported songs from queue."
    }

    fun scanDeviceStorage() {
        viewModelScope.launch {
            _isScanning.value = true
            _storageStatus.value = "Scanning local system resources..."
            delay(500)
            refreshTrackLibrary(getApplication())
            _storageStatus.value = "Synced device memory library tracks."
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
