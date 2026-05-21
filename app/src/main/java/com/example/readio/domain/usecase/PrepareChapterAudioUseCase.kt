package com.example.readio.domain.usecase

import javax.inject.Inject

import com.example.readio.domain.model.Chapter
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.ChapterAudioState
import com.example.readio.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class PrepareChapterAudioUseCase @Inject constructor(
    private val audioRepository: AudioRepository,
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(chapter: Chapter): Flow<ChapterAudioState> = flow {
        val config = settingsRepository.getTtsConfig()
        emitAll(audioRepository.getChapterAudio(chapter, config))
    }
}
