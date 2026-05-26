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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChapterListUiState(
    val bookId: String = "",
    val bookTitle: String = "",
    val chapters: List<ChapterIndex> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ChapterListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val epubRepository: EpubRepository,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val _book = MutableStateFlow<EpubBook?>(null)

    val uiState: StateFlow<ChapterListUiState> = _book
        .map { book ->
            ChapterListUiState(
                bookId    = bookId,
                bookTitle = book?.title ?: "",
                chapters  = book?.chapters ?: emptyList(),
                isLoading = book == null,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterListUiState())

    init {
        viewModelScope.launch {
            _book.value = epubRepository.observeBooks()
                .mapNotNull { list -> list.find { it.id == bookId } }
                .first()
        }
    }
}
