package com.example.readio.domain.model

sealed class ChapterAudioStatus {
    data object NotDownloaded : ChapterAudioStatus()
    data class Downloading(val progress: Float) : ChapterAudioStatus()
    data object Downloaded : ChapterAudioStatus()
    data class Error(val message: String) : ChapterAudioStatus()
}
