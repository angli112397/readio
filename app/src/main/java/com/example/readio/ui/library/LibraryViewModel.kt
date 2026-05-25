package com.example.readio.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.TtsProvider
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.usecase.DeleteBookUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val epubRepository: EpubRepository,
    private val deleteBook: DeleteBookUseCase,
) : ViewModel() {

    val books = epubRepository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                epubRepository.importBook(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(book: EpubBook) {
        viewModelScope.launch { deleteBook(book) }
    }

    fun clearError() { _error.value = null }

    fun setBookTts(bookId: String, provider: TtsProvider?, voiceId: String?) {
        viewModelScope.launch {
            epubRepository.updateBookTts(bookId, provider, voiceId)
        }
    }
}
