package com.example.readio.domain.model

sealed class ChapterAudioStatus {
    /** Provider is realtime (LOCAL_ANDROID) — no pre-download needed. */
    data object NotApplicable : ChapterAudioStatus()
    data object NotDownloaded : ChapterAudioStatus()
    /**
     * An async TTS task has been submitted but the result has not been fetched yet.
     * [taskId] is persisted on disk so it survives app restarts.
     * The user can trigger a download (which resumes from this task ID) from the chapter list.
     */
    data class HasTaskId(val taskId: String) : ChapterAudioStatus()
    /** Actively synthesizing or downloading. [label] is a user-visible hint. */
    data class Downloading(val progress: Float, val label: String = "") : ChapterAudioStatus()
    data object Downloaded : ChapterAudioStatus()
    data class Error(val message: String) : ChapterAudioStatus()
}
