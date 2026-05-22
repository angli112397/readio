package com.example.readio.domain.model

enum class ReadingTheme(val label: String) {
    DEFAULT("默认"),
    WARM("暖黄"),
    SEPIA("羊皮纸"),
    NIGHT("夜间")
}

data class ReadingPreferences(
    val chunkSize: Int = 150,
    val fontSize: Int = 16,             // sp
    val lineHeightMultiplier: Float = 1.5f,
    val readingTheme: ReadingTheme = ReadingTheme.DEFAULT
)
