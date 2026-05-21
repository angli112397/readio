package com.example.readio.ui.reader

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.readio.PlaybackService
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.ReadingPosition
import com.example.readio.domain.repository.ChapterAudioState
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.ReadingProgressRepository
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.usecase.DownloadChapterAudioUseCase
import com.example.readio.domain.usecase.GetReadingPositionUseCase
import com.example.readio.domain.usecase.PrepareChapterAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

data class ReaderUiState(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapter: Chapter? = null,
    val currentParagraphIndex: Int = 0,
    val hasPrevChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val audioGenerating: Boolean = false,
    val audioProgress: Float = 0f,
    val chapterAudio: ChapterAudio? = null,
    val audioError: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val epubRepository: EpubRepository,
    private val progressRepository: ReadingProgressRepository,
    private val settingsRepository: SettingsRepository,
    private val getReadingPosition: GetReadingPositionUseCase,
    private val prepareChapterAudio: PrepareChapterAudioUseCase,
    private val downloadChapterAudio: DownloadChapterAudioUseCase
) : ViewModel() {

    val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val startChapterId: String? = savedStateHandle["startChapterId"]

    companion object {
        private const val TAG = "ReaderVM"
        private const val PREFETCH_AHEAD = 2
    }

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState = _uiState.asStateFlow()

    private var currentBook: EpubBook? = null
    private var chapterLoadJob: Job? = null
    private var audioJob: Job? = null
    private var prefetchJob: Job? = null

    private lateinit var _player: MediaController

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val newIndex = _player.currentMediaItemIndex
            updateParagraphIndex(newIndex)
            viewModelScope.launch {
                val chapter = _uiState.value.chapter ?: return@launch
                progressRepository.savePosition(ReadingPosition(bookId, chapter.id, newIndex))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) _uiState.update { it.copy(isPlaying = false) }
        }
    }

    init {
        viewModelScope.launch {
            _player = connectToService()
            _player.addListener(playerListener)

            settingsRepository.observeTtsConfig()
                .onEach { _player.setPlaybackSpeed(it.speechRate) }
                .launchIn(viewModelScope)

            val book = epubRepository.observeBooks()
                .mapNotNull { list -> list.find { it.id == bookId } }
                .first()
                .also { currentBook = it }
            val position = startChapterId?.let { ReadingPosition(book.id, it, 0) }
                ?: getReadingPosition(book)
            loadChapterAt(book, position)
        }
    }

    // ---- Public actions ----

    fun onParagraphTap(index: Int) {
        val chapter = _uiState.value.chapter ?: return
        val state = _uiState.value

        // When audio advances and the wheel snaps to the new paragraph, isScrollInProgress
        // briefly goes false and fires onCenterChanged with the current index. Guard against
        // that triggering a seek back to the same position.
        val isAudioDrivenCallback = state.isPlaying && index == state.currentParagraphIndex

        updateParagraphIndex(index)
        viewModelScope.launch { progressRepository.savePosition(ReadingPosition(bookId, chapter.id, index)) }

        if (isAudioDrivenCallback) return
        if (_player.mediaItemCount > index) _player.seekTo(index, 0L)
    }

    fun onPlayPause() {
        if (_player.isPlaying) { _player.pause(); return }
        if (_player.mediaItemCount > 0) { _player.play(); return }
        generateAndPlay()
    }

    fun dismissAudioError() = _uiState.update { it.copy(audioError = null) }

    fun navigatePrevChapter() = navigateRelative(-1)
    fun navigateNextChapter() = navigateRelative(+1)

    // ---- Private ----

    private fun buildMediaItem(file: File): MediaItem {
        val state = _uiState.value
        return MediaItem.Builder()
            .setUri(file.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.chapterTitle)
                    .setArtist(state.bookTitle)
                    .build()
            )
            .build()
    }

    private fun generateAndPlay() {
        val chapter = _uiState.value.chapter ?: return
        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            try {
                val startIndex = _uiState.value.currentParagraphIndex
                var playbackStarted = false
                val bufferSize = minOf(5, chapter.paragraphCount)

                prepareChapterAudio(chapter).collect { audioState ->
                    when (audioState) {
                        is ChapterAudioState.Generating -> _uiState.update {
                            it.copy(
                                audioGenerating = !playbackStarted,
                                audioProgress = audioState.done.toFloat() / audioState.total
                            )
                        }
                        is ChapterAudioState.ParagraphReady -> {
                            _player.addMediaItem(buildMediaItem(audioState.file))
                            if (!playbackStarted && _player.mediaItemCount >= startIndex + bufferSize) {
                                _player.seekTo(startIndex, 0L)
                                _player.prepare()
                                _player.play()
                                playbackStarted = true
                                _uiState.update { it.copy(audioGenerating = false) }
                                prefetchNextChapters()
                            }
                        }
                        is ChapterAudioState.Ready -> {
                            if (!playbackStarted) {
                                val items = audioState.audio.paragraphFiles.map { buildMediaItem(it) }
                                _player.setMediaItems(items, startIndex, 0L)
                                _player.prepare()
                                _player.play()
                                prefetchNextChapters()
                            }
                            _uiState.update {
                                it.copy(audioGenerating = false, chapterAudio = audioState.audio)
                            }
                        }
                        is ChapterAudioState.Error -> _uiState.update {
                            it.copy(audioGenerating = false, audioError = audioState.message)
                        }
                        else -> Unit
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Audio flow error", e)
                _uiState.update { it.copy(audioGenerating = false, audioError = e.message) }
            }
        }
    }

    private fun prefetchNextChapters() {
        val book = currentBook ?: return
        val chapterIdx = _uiState.value.chapter?.indexInBook ?: return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            for (offset in 1..PREFETCH_AHEAD) {
                val nextIdx = chapterIdx + offset
                if (nextIdx >= book.chapters.size) break
                runCatching { downloadChapterAudio(book.id, book.chapters[nextIdx].id).collect {} }
            }
        }
    }

    private fun navigateRelative(delta: Int) {
        val book = currentBook ?: return
        val currentIndex = _uiState.value.chapter?.indexInBook ?: return
        val targetIndex = currentIndex + delta
        if (targetIndex !in book.chapters.indices) return
        chapterLoadJob?.cancel()
        chapterLoadJob = viewModelScope.launch {
            loadChapterAt(book, ReadingPosition(bookId, book.chapters[targetIndex].id, 0))
        }
    }

    private suspend fun loadChapterAt(book: EpubBook, position: ReadingPosition) {
        audioJob?.cancel()
        prefetchJob?.cancel()
        _player.stop()
        _player.clearMediaItems()
        _uiState.update {
            it.copy(
                isLoading = true, error = null,
                chapterAudio = null, isPlaying = false, audioGenerating = false
            )
        }
        runCatching { epubRepository.loadChapter(position.bookId, position.chapterId) }
            .onSuccess { chapter ->
                _uiState.update {
                    it.copy(
                        bookTitle = book.title,
                        chapterTitle = chapter.title,
                        chapter = chapter,
                        currentParagraphIndex = position.indexInChapter,
                        hasPrevChapter = chapter.indexInBook > 0,
                        hasNextChapter = chapter.indexInBook < book.chapterCount - 1,
                        isLoading = false
                    )
                }
                progressRepository.savePosition(position)
            }
            .onFailure { e ->
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
    }

    private fun updateParagraphIndex(index: Int) {
        _uiState.update { it.copy(currentParagraphIndex = index) }
    }

    private suspend fun connectToService(): MediaController {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return suspendCancellableCoroutine { cont ->
            val future = MediaController.Builder(context, sessionToken).buildAsync()
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { future.cancel(false) }
        }
    }

    override fun onCleared() {
        audioJob?.cancel()
        prefetchJob?.cancel()
        if (::_player.isInitialized) {
            _player.stop()
            _player.clearMediaItems()
            _player.release()
        }
    }
}
