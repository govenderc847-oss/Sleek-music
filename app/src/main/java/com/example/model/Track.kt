package com.example.model

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val streamUrl: String,
    val durationMs: Long,
    val durationString: String,
    val genre: String,
    val colorStart: Long,
    val colorEnd: Long,
    val lyrics: List<LyricLine> = emptyList(),
    val coverUri: String? = null
)

data class LyricLine(
    val timeMs: Long,
    val text: String
)
