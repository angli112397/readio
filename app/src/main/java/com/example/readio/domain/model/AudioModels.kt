package com.example.readio.domain.model

import java.io.File

enum class TtsProvider(val displayName: String, val persistAudio: Boolean = true) {
    AZURE("Microsoft Azure"),
    LOCAL_ANDROID("系统 TTS（本地）", persistAudio = false)
}

data class TtsConfig(
    val provider: TtsProvider = TtsProvider.AZURE,
    val apiKey: String = "",
    val region: String = "eastasia",
    val voice: String = "zh-CN-XiaoxiaoNeural",
    val speechRate: Float = 1.0f
) {
    // Rate is applied locally by ExoPlayer, not baked into audio — cache key excludes it.
    val cacheKey: String get() = "${provider.name}|$voice"
}

/**
 * Chapter audio where each file corresponds to one paragraph by index.
 * paragraphFiles[i] is the MP3 for chapter.paragraphs[i].
 */
data class ChapterAudio(
    val chapterId: String,
    val paragraphFiles: List<File>,
    val config: TtsConfig
) {
    val paragraphCount: Int get() = paragraphFiles.size
    fun fileAt(index: Int): File? = paragraphFiles.getOrNull(index)
}
