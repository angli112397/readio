package com.example.readio.ui.chapters

import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.repository.AudioRepository
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.usecase.DownloadChapterAudioUseCase
import com.example.readio.domain.usecase.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadManagerState(
    val statusMap: Map<String, ChapterAudioStatus> = emptyMap(),
    val isBulkDownloading: Boolean = false,
    val bulkDone: Int = 0,
    val bulkTotal: Int = 0
)

@Singleton
class AudioDownloadManager @Inject constructor(
    private val downloadChapterAudio: DownloadChapterAudioUseCase,
    private val audioRepository: AudioRepository,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DownloadManagerState())
    val state: StateFlow<DownloadManagerState> = _state.asStateFlow()

    private var bulkJob: Job? = null

    fun checkStatuses(chapters: List<ChapterIndex>) {
        scope.launch {
            val unknown = chapters.filter { _state.value.statusMap[it.id] == null }
            if (unknown.isEmpty()) return@launch
            val config = settingsRepository.getTtsConfig()
            val statuses = coroutineScope {
                unknown.map { chapter ->
                    async {
                        val downloaded = audioRepository.hasChapterAudio(chapter.id, config)
                        chapter.id to if (downloaded) ChapterAudioStatus.Downloaded
                                      else ChapterAudioStatus.NotDownloaded
                    }
                }.map { it.await() }
            }
            _state.update { it.copy(statusMap = it.statusMap + statuses) }
        }
    }

    fun downloadChapter(bookId: String, chapter: ChapterIndex) {
        if (_state.value.statusMap[chapter.id] is ChapterAudioStatus.Downloading) return
        scope.launch { downloadSingle(bookId, chapter) }
    }

    fun downloadAll(bookId: String, chapters: List<ChapterIndex>) {
        bulkJob?.cancel()
        val pending = chapters.filter { _state.value.statusMap[it.id] !is ChapterAudioStatus.Downloaded }
        if (pending.isEmpty()) return
        _state.update { it.copy(isBulkDownloading = true, bulkDone = 0, bulkTotal = pending.size) }
        bulkJob = scope.launch {
            try {
                for (chapter in pending) {
                    if (!isActive) break
                    downloadSingle(bookId, chapter)
                    _state.update { it.copy(bulkDone = it.bulkDone + 1) }
                }
            } finally {
                _state.update { it.copy(isBulkDownloading = false) }
            }
        }
    }

    fun cancelBulk() {
        bulkJob?.cancel()
        _state.update { it.copy(isBulkDownloading = false) }
    }

    fun clearChapter(chapterId: String) {
        scope.launch {
            audioRepository.clearChapterAudio(chapterId)
            _state.update { it.copy(statusMap = it.statusMap + (chapterId to ChapterAudioStatus.NotDownloaded)) }
        }
    }

    private suspend fun downloadSingle(bookId: String, chapter: ChapterIndex) {
        _state.update { it.copy(statusMap = it.statusMap + (chapter.id to ChapterAudioStatus.Downloading(0f))) }
        try {
            downloadChapterAudio(bookId, chapter.id).collect { progress ->
                when (progress) {
                    is DownloadProgress.InProgress -> _state.update {
                        it.copy(statusMap = it.statusMap + (chapter.id to
                            ChapterAudioStatus.Downloading(progress.done.toFloat() / progress.total)))
                    }
                    is DownloadProgress.Complete -> _state.update {
                        it.copy(statusMap = it.statusMap + (chapter.id to ChapterAudioStatus.Downloaded))
                    }
                    is DownloadProgress.Failed -> _state.update {
                        it.copy(statusMap = it.statusMap + (chapter.id to ChapterAudioStatus.Error(progress.message)))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(statusMap = it.statusMap + (chapter.id to ChapterAudioStatus.NotDownloaded)) }
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(statusMap = it.statusMap + (chapter.id to ChapterAudioStatus.Error(e.message ?: "Unknown error")))
            }
        }
    }
}
