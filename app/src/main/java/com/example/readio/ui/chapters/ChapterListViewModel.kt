package com.example.readio.ui.chapters

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.domain.manager.AudioDownloadManager
import com.example.readio.domain.model.ChapterAudioStatus
import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChapterUiItem(
    val chapterIndex: ChapterIndex,
    val audioStatus: ChapterAudioStatus = ChapterAudioStatus.NotDownloaded
)

data class ChapterListUiState(
    val bookId: String = "",
    val bookTitle: String = "",
    val chapters: List<ChapterUiItem> = emptyList(),
    val isLoading: Boolean = true,
    /** True when the active TTS provider supports pre-download (batch engines). */
    val isDownloadableProvider: Boolean = false,
    val isBulkDownloading: Boolean = false,
    val bulkDone: Int = 0,
    val bulkTotal: Int = 0
)

@HiltViewModel
class ChapterListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val epubRepository: EpubRepository,
    private val downloadManager: AudioDownloadManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val _book = MutableStateFlow<EpubBook?>(null)

    val uiState: StateFlow<ChapterListUiState> = combine(
        _book, downloadManager.state, settingsRepository.observeTtsConfig()
    ) { book, dlState, ttsConfig ->
        ChapterListUiState(
            bookId               = bookId,
            bookTitle            = book?.title ?: "",
            chapters             = book?.chapters?.map { c ->
                ChapterUiItem(c, dlState.statusMap[c.id] ?: ChapterAudioStatus.NotDownloaded)
            } ?: emptyList(),
            isLoading            = book == null,
            isDownloadableProvider = ttsConfig.provider == TtsProvider.VOLCENGINE,
            isBulkDownloading    = dlState.isBulkDownloading,
            bulkDone             = dlState.bulkDone,
            bulkTotal            = dlState.bulkTotal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterListUiState())

    /** One-shot messages forwarded from the download manager. */
    val messages = downloadManager.messages

    init {
        viewModelScope.launch {
            val book = epubRepository.observeBooks()
                .mapNotNull { list -> list.find { it.id == bookId } }
                .first()
            _book.value = book
            downloadManager.checkStatuses(book.chapters)
        }
    }

    /**
     * Start (or resume) downloading audio for [item].
     *
     * If the chapter has a [ChapterAudioStatus.HasTaskId], the engine automatically picks up
     * the saved task ID and continues polling without re-submitting.
     */
    fun downloadChapter(item: ChapterUiItem) =
        downloadManager.downloadChapter(bookId, item.chapterIndex)

    /**
     * Persist an externally-obtained task ID without making any API call.
     * Useful if the user obtained a task ID from a previous session.
     */
    fun importTaskId(chapterId: String, taskId: String) =
        downloadManager.importTaskId(chapterId, taskId)

    /** Start downloading all chapters that are not yet downloaded. */
    fun downloadAll() {
        val chapters = _book.value?.chapters ?: return
        downloadManager.downloadAll(bookId, chapters)
    }

    fun cancelBulkDownload() = downloadManager.cancelBulk()

    fun clearChapter(item: ChapterUiItem) =
        downloadManager.clearChapter(item.chapterIndex.id)
}
