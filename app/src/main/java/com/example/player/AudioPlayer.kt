package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

sealed interface PlaybackState {
    object Idle : PlaybackState
    object Loading : PlaybackState
    object Playing : PlaybackState
    object Paused : PlaybackState
    data class Error(val message: String) : PlaybackState
}

class AudioPlayer(private val context: Context, private val onTrackCompleted: () -> Unit) {
    private val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null
    
    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _amplitudeSpectrum = MutableStateFlow(FloatArray(24) { 0.1f })
    val amplitudeSpectrum: StateFlow<FloatArray> = _amplitudeSpectrum.asStateFlow()

    private var progressJob: Job? = null
    private var visualizerJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setupMediaPlayer()
    }

    private fun setupMediaPlayer() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener {
                    Log.d(TAG, "MediaPlayer prepared successfully")
                    _playbackState.value = PlaybackState.Playing
                    start()
                    startProgressTicker()
                    startVisualizerTicker()
                }
                setOnCompletionListener {
                    Log.d(TAG, "MediaPlayer track completed")
                    stopProgressTicker()
                    stopVisualizerTicker()
                    _playbackState.value = PlaybackState.Idle
                    _currentPosition.value = 0L
                    onTrackCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error occurred: what=$what, extra=$extra")
                    _playbackState.value = PlaybackState.Error("Failed to stream: error $what")
                    stopProgressTicker()
                    stopVisualizerTicker()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate MediaPlayer", e)
            _playbackState.value = PlaybackState.Error("Engine failed: ${e.localizedMessage}")
        }
    }

    fun playTrack(track: Track) {
        mainScope.launch {
            if (_currentTrack.value?.id == track.id && _playbackState.value is PlaybackState.Paused) {
                // Resume existing track
                resume()
                return@launch
            }

            Log.d(TAG, "Loading new track: ${track.title} from ${track.streamUrl}")
            _currentTrack.value = track
            _playbackState.value = PlaybackState.Loading
            _currentPosition.value = 0L

            stopProgressTicker()
            stopVisualizerTicker()

            try {
                mediaPlayer?.apply {
                    reset()
                    // Set data source with context to prevent permission/uri issues
                    setDataSource(context, Uri.parse(track.streamUrl))
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting data source for track", e)
                _playbackState.value = PlaybackState.Error("Source load failed: ${e.localizedMessage}")
            }
        }
    }

    fun pause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            _playbackState.value = PlaybackState.Paused
            stopProgressTicker()
            stopVisualizerTicker()
            settleVisualizer()
        }
    }

    fun resume() {
        if (_playbackState.value is PlaybackState.Paused || _playbackState.value is PlaybackState.Idle) {
            mediaPlayer?.start()
            _playbackState.value = PlaybackState.Playing
            startProgressTicker()
            startVisualizerTicker()
        }
    }

    fun togglePlayPause() {
        val state = _playbackState.value
        Log.d(TAG, "Toggling playback. Current state: $state")
        if (state is PlaybackState.Playing) {
            pause()
        } else if (state is PlaybackState.Paused) {
            resume()
        } else {
            _currentTrack.value?.let { playTrack(it) }
        }
    }

    fun updateCurrentTrackDetails(track: Track) {
        if (_currentTrack.value?.id == track.id) {
            _currentTrack.value = track
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { player ->
            try {
                player.seekTo(positionMs.toInt())
                _currentPosition.value = positionMs
            } catch (e: Exception) {
                Log.e(TAG, "Seek error", e)
            }
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = mainScope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        _currentPosition.value = player.currentPosition.toLong()
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun startVisualizerTicker() {
        visualizerJob?.cancel()
        visualizerJob = mainScope.launch {
            val random = Random(System.currentTimeMillis())
            var phase = 0f
            while (isActive) {
                phase += 0.2f
                val list = FloatArray(24) { i ->
                    val wave = Math.sin((phase + i * 0.5f).toDouble()).toFloat()
                    val noise = random.nextFloat() * 0.3f
                    // Scaled magnitude from 0.1 to 1.0 depending on position in loop
                    (0.35f + 0.45f * wave + noise).coerceIn(0.12f, 1.0f)
                }
                _amplitudeSpectrum.value = list
                delay(60) // High FPS animation ticker: ~16fps
            }
        }
    }

    private fun stopVisualizerTicker() {
        visualizerJob?.cancel()
        visualizerJob = null
    }

    private fun settleVisualizer() {
        mainScope.launch {
            val start = _amplitudeSpectrum.value.clone()
            // Animate visualizer settling down to flatline
            for (step in 1..8) {
                val factor = 1f - (step / 8f)
                val current = FloatArray(24) { i ->
                    (start[i] * factor).coerceAtLeast(0.08f)
                }
                _amplitudeSpectrum.value = current
                delay(30)
            }
            _amplitudeSpectrum.value = FloatArray(24) { 0.08f }
        }
    }

    // Hardware dynamic audio fx
    private var equalizerFx: android.media.audiofx.Equalizer? = null
    private var virtualizerFx: android.media.audiofx.Virtualizer? = null
    private var bassBoostFx: android.media.audiofx.BassBoost? = null

    fun applyEqSettings(settings: com.example.ui.viewmodel.EqualizerSettings) {
        val sessionId = mediaPlayer?.audioSessionId ?: return
        if (sessionId == 0) return
        
        try {
            if (equalizerFx == null || equalizerFx?.id != sessionId) {
                equalizerFx?.release()
                equalizerFx = android.media.audiofx.Equalizer(0, sessionId).apply { enabled = true }
            }
            equalizerFx?.let { eq ->
                eq.enabled = true
                val bandsCount = eq.numberOfBands.toInt()
                for (i in 0 until bandsCount.coerceAtMost(5)) {
                    val range = eq.bandLevelRange ?: shortArrayOf(-1500, 1500)
                    val minLevel = range[0]
                    val maxLevel = range[1]
                    val bandVal = settings.bands.getOrNull(i) ?: 0.5f // 0..1
                    val targetLevel = (minLevel + bandVal * (maxLevel - minLevel)).toInt().toShort()
                    eq.setBandLevel(i.toShort(), targetLevel)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply Equalizer FX: ${e.localizedMessage}")
        }

        try {
            if (virtualizerFx == null || virtualizerFx?.id != sessionId) {
                virtualizerFx?.release()
                virtualizerFx = android.media.audiofx.Virtualizer(0, sessionId).apply { enabled = true }
            }
            virtualizerFx?.let { virt ->
                virt.enabled = settings.spatialAudio > 0.05f
                if (settings.spatialAudio > 0.05f) {
                    virt.setStrength((settings.spatialAudio * 1000).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply Virtualizer FX: ${e.localizedMessage}")
        }

        try {
            if (bassBoostFx == null || bassBoostFx?.id != sessionId) {
                bassBoostFx?.release()
                bassBoostFx = android.media.audiofx.BassBoost(0, sessionId).apply { enabled = true }
            }
            bassBoostFx?.let { bb ->
                bb.enabled = settings.bassBoost > 0.05f
                if (settings.bassBoost > 0.05f) {
                    bb.setStrength((settings.bassBoost * 1000).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply BassBoost FX: ${e.localizedMessage}")
        }
    }

    fun release() {
        mainScope.cancel()
        stopProgressTicker()
        stopVisualizerTicker()
        _playbackState.value = PlaybackState.Idle
        
        try {
            equalizerFx?.release()
            virtualizerFx?.release()
            bassBoostFx?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
        equalizerFx = null
        virtualizerFx = null
        bassBoostFx = null

        mediaPlayer?.apply {
            try {
                if (isPlaying) {
                     stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer", e)
            }
        }
        mediaPlayer = null
    }
}
