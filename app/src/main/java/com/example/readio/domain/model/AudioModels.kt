package com.example.readio.domain.model

import java.io.File

enum class TtsProvider(val displayName: String) {
    AZURE("Microsoft Azure"),
    LOCAL_ANDROID("系统 TTS（本地）")
}

data class TtsConfig(
    val provider: TtsProvider = TtsProvider.AZURE,
    val apiKey: String = "",
    val region: String = "eastasia",
    val voice: String = "zh-CN-XiaoxiaoNeural",
    val speechRate: Float = 1.0f
) {
    // Rate is applied locally by ExoPlayer — cache key excludes it.
    val cacheKey: String get() = "${provider.name}|$voice"
}

/** Chapter audio where chunkFiles[i] is the MP3 for chapter.chunks[i]. */
data class ChapterAudio(
    val chapterId: String,
    val chunkFiles: List<File>,
    val config: TtsConfig
) {
    val chunkCount: Int get() = chunkFiles.size
    fun fileAt(index: Int): File? = chunkFiles.getOrNull(index)
}
