package com.example.readio.ui.chapters

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.domain.model.ChapterIndex
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.repository.EpubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ChapterAudioStatus {
    data object NotDownloaded : ChapterAudioStatus()
    data class Downloading(val progress: Float) : ChapterAudioStatus()
    data object Downloaded : ChapterAudioStatus()
    data class Error(val message: String) : ChapterAudioStatus()
}

data class ChapterUiItem(
    val chapterIndex: ChapterIndex,
    val audioStatus: ChapterAudioStatus = ChapterAudioStatus.NotDownloaded
)

data class ChapterListUiState(
    val bookId: String = "",
    val bookTitle: String = "",
    val chapters: List<ChapterUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val isBulkDownloading: Boolean = false,
    val bulkDone: Int = 0,
    val bulkTotal: Int = 0
)

@HiltViewModel
class ChapterListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val epubRepository: EpubRepository,
    private val downloadManager: AudioDownloadManager
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val _book = MutableStateFlow<EpubBook?>(null)

    val uiState: StateFlow<ChapterListUiState> = combine(
        _book, downloadManager.state
    ) { book, dlState ->
        ChapterListUiState(
            bookId = bookId,
            bookTitle = book?.title ?: "",
            chapters = book?.chapters?.map { c ->
                ChapterUiItem(c, dlState.statusMap[c.id] ?: ChapterAudioStatus.NotDownloaded)
            } ?: emptyList(),
            isLoading = book == null,
            isBulkDownloading = dlState.isBulkDownloading,
            bulkDone = dlState.bulkDone,
            bulkTotal = dlState.bulkTotal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterListUiState())

    init {
        viewModelScope.launch {
            val book = epubRepository.observeBooks()
                .mapNotNull { list -> list.find { it.id == bookId } }
                .first()
            _book.value = book
            downloadManager.checkStatuses(book.chapters)
        }
    }

    fun downloadChapter(item: ChapterUiItem) =
        downloadManager.downloadChapter(bookId, item.chapterIndex)

    fun downloadAll() {
        val chapters = _book.value?.chapters ?: return
        downloadManager.downloadAll(bookId, chapters)
    }

    fun cancelBulkDownload() = downloadManager.cancelBulk()

    fun clearChapter(item: ChapterUiItem) =
        downloadManager.clearChapter(item.chapterIndex.id)
}
