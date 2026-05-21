package com.example.readio.domain.repository

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.TtsConfig
import java.io.File
import kotlinx.coroutines.flow.Flow

sealed class ChapterAudioState {
    data object Idle : ChapterAudioState()
    data class Generating(val done: Int, val total: Int) : ChapterAudioState()
    data class ParagraphReady(val index: Int, val file: File) : ChapterAudioState()
    data class Ready(val audio: ChapterAudio) : ChapterAudioState()
    data class Error(val message: String) : ChapterAudioState()
}

interface AudioRepository {
    fun getChapterAudio(chapter: Chapter, config: TtsConfig): Flow<ChapterAudioState>
    suspend fun hasChapterAudio(chapterId: String, config: TtsConfig): Boolean
    suspend fun clearChapterAudio(chapterId: String)
    suspend fun clearAllAudio()
}
