package com.example.readio.domain.usecase

import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

sealed class DownloadProgress {
    data class InProgress(val done: Int, val total: Int) : DownloadProgress()
    data object Complete : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
}

class DownloadChapterAudioUseCase @Inject constructor(
    private val epubRepository: EpubRepository,
    private val audioRepository: AudioRepository,
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(bookId: String, chapterId: String): Flow<DownloadProgress> = flow {
        val ttsConfig = settingsRepository.getTtsConfig()
        val prefs = settingsRepository.getReadingPreferences()
        if (audioRepository.hasChapterAudio(chapterId, ttsConfig, prefs.chunkSize)) {
            emit(DownloadProgress.Complete)
            return@flow
        }
        val chapter = epubRepository.loadChapter(bookId, chapterId, prefs.chunkSize)
        emitAll(audioRepository.getChapterAudio(chapter, ttsConfig).toDownloadProgress())
    }

    private fun Flow<ChapterAudioState>.toDownloadProgress(): Flow<DownloadProgress> =
        mapNotNull { state ->
            when (state) {
                is ChapterAudioState.Generating -> DownloadProgress.InProgress(state.done, state.total)
                is ChapterAudioState.Ready      -> DownloadProgress.Complete
                is ChapterAudioState.Error      -> DownloadProgress.Failed(state.message)
                else                            -> null
            }
        }
}
