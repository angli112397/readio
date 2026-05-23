package com.example.readio.domain.model

sealed class ChapterAudioStatus {
    /** Provider is LOCAL_ANDROID — no download needed, always real-time. */
    data object NotApplicable : ChapterAudioStatus()
    data object NotDownloaded : ChapterAudioStatus()
    /**
     * A Volcengine async-TTS task has been submitted but the result has not been fetched yet.
     * [taskId] is persisted to disk so it survives app restarts.
     * The user triggers result fetching explicitly from the chapter list.
     */
    data class HasTaskId(val taskId: String) : ChapterAudioStatus()
    /** Actively downloading the completed audio from Volcengine's CDN. */
    data class Downloading(val progress: Float) : ChapterAudioStatus()
    data object Downloaded : ChapterAudioStatus()
    data class Error(val message: String) : ChapterAudioStatus()
}
