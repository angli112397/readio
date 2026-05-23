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
import com.example.readio.domain.model.AudioSource
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.ReadingPosition
import com.example.readio.domain.model.ReadingPreferences
import com.example.readio.domain.repository.ChapterAudioState
import com.example.readio.domain.repository.EpubRepository
import com.example.readio.domain.repository.ReadingProgressRepository
import com.example.readio.domain.repository.SettingsRepository
import com.example.readio.domain.repository.VocabularyRepository
import com.example.readio.domain.usecase.GetReadingPositionUseCase
import com.example.readio.domain.usecase.PrepareChapterAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

data class WordLookup(
    val word: String,
    val translation: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

data class ReaderUiState(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapter: Chapter? = null,
    val currentChunkIndex: Int = 0,
    val hasPrevChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val audioGenerating: Boolean = false,
    val audioProgress: Float = 0f,
    val audioError: String? = null,
    val wordLookup: WordLookup? = null
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
    private val vocabularyRepository: VocabularyRepository
) : ViewModel() {

    val bookId: String = checkNotNull(savedStateHandle["bookId"])
    private val startChapterId: String? = savedStateHandle["startChapterId"]

    companion object {
        private const val TAG = "ReaderVM"
        private const val PREFETCH_AHEAD = 2
        /** Sliding window: keep this many already-played sentences on disk for backward seeking. */
        private const val LOCAL_TTS_KEEP_BACK = 2
    }

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState = _uiState.asStateFlow()

    val readingPrefs = settingsRepository.observeReadingPreferences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPreferences())

    private var currentBook: EpubBook? = null
    private var chapterLoadJob: Job? = null
    private var audioJob: Job? = null
    private var prefetchJob: Job? = null
    private var lookupJob: Job? = null
    /** Polls ExoPlayer position every 150 ms to drive chunk sync for SingleFile (Volcengine). */
    private var positionTrackJob: Job? = null

    /** Current resolved audio — holds sentenceToChunk for sync regardless of AudioSource type. */
    private var currentAudio: ChapterAudio? = null

    /**
     * Sentence index of ExoPlayer playlist item 0.
     * Mirrors [ChapterAudio.playlistOffset] but available from the moment playback starts,
     * even during the LOCAL_ANDROID streaming phase before [currentAudio] is resolved.
     * Reset to 0 on chapter load and [stopAndClearAudio].
     */
    private var currentPlaylistOffset: Int = 0

    private lateinit var _player: MediaController

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val playlistIndex = _player.currentMediaItemIndex
            val chunkIndex = playlistIndexToChunk(playlistIndex) ?: return
            updateChunkIndex(chunkIndex)
            viewModelScope.launch {
                val chapter = _uiState.value.chapter ?: return@launch
                progressRepository.savePosition(ReadingPosition(bookId, chapter.id, chunkIndex))
            }
            // Sliding window: keep current ± LOCAL_TTS_KEEP_BACK files for backward seeking.
            // Delete the file that just slid out of the back of the window.
            deleteLocalTtsFile(playlistIndex - 1 - LOCAL_TTS_KEEP_BACK)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                positionTrackJob?.cancel()
                _uiState.update { it.copy(isPlaying = false) }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.w(TAG, "Player error ${error.errorCode}: ${error.message}")
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                // Sliding window cleaned up the file the player tried to access.
                // Stop quietly — user can re-press play to synthesize from the current position.
                stopAndClearAudio()
            } else {
                _uiState.update { it.copy(isPlaying = false, audioError = error.message) }
            }
        }
    }

    init {
        viewModelScope.launch {
            _player = connectToService()
            _player.addListener(playerListener)

            settingsRepository.observeTtsConfig()
                .onEach { _player.setPlaybackSpeed(it.speechRate) }
                .launchIn(viewModelScope)

            // Discard stale audio when provider or voice changes (cacheKey encodes both).
            // speechRate changes are handled above via setPlaybackSpeed — no restart needed.
            settingsRepository.observeTtsConfig()
                .map { it.cacheKey }
                .distinctUntilChanged()
                .drop(1)   // skip initial emission — audio is not stale at startup
                .onEach { stopAndClearAudio() }
                .launchIn(viewModelScope)

            readingPrefs
                .onEach { prefs ->
                    val chapter = _uiState.value.chapter ?: return@onEach
                    val book = currentBook ?: return@onEach
                    if (prefs.chunkSize != chapter.chunkSize) {
                        chapterLoadJob?.cancel()
                        chapterLoadJob = viewModelScope.launch {
                            loadChapterAt(book, ReadingPosition(bookId, chapter.id, 0))
                        }
                    }
                }
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

    // ── Public actions ────────────────────────────────────────────────────────

    /**
     * Called when the user's scroll gesture starts (NestedScrollSource.UserInput in ChunkWheel).
     * Stops playback immediately so browsing and playing are always mutually exclusive.
     * Audio-driven programmatic scrolls (animateScrollToItem) do NOT call this.
     */
    fun onScrollStarted() {
        if (_uiState.value.isPlaying) stopAndClearAudio()
    }

    /**
     * Called when ChunkWheel settles on a new center item — either after a user scroll
     * (audio already stopped by [onScrollStarted]) or after audio auto-advance
     * (programmatic scroll, audio still playing).
     */
    fun onChunkTap(index: Int) {
        val state = _uiState.value
        val chapter = state.chapter ?: return
        // Audio-driven case: ViewModel updated currentChunkIndex, ChunkWheel snapped
        // programmatically and called back here — skip, audio keeps playing.
        if (state.isPlaying && index == state.currentChunkIndex) return
        updateChunkIndex(index)
        viewModelScope.launch { progressRepository.savePosition(ReadingPosition(bookId, chapter.id, index)) }
        // No seek needed — onScrollStarted() already stopped audio before scroll settled.
    }

    fun onPlayPause() {
        // Both manual pause and scroll-to-stop call stopAndClearAudio(), keeping them identical:
        // audio and position tracking stop immediately, ChunkWheel stays wherever it is.
        // Pressing play always re-synthesises / re-loads from the current chunk position.
        if (_player.isPlaying || _uiState.value.audioGenerating) {
            stopAndClearAudio(); return
        }
        generateAndPlay()
    }

    fun dismissAudioError() = _uiState.update { it.copy(audioError = null) }

    fun onTranslateTap() {
        val state = _uiState.value
        val chunkText = state.chapter?.chunks?.getOrNull(state.currentChunkIndex)?.text ?: return

        if (state.wordLookup?.word == chunkText && !state.wordLookup.isLoading) {
            dismissWordLookup(); return
        }

        lookupJob?.cancel()
        _uiState.update { it.copy(wordLookup = WordLookup(word = chunkText)) }
        lookupJob = viewModelScope.launch {
            val targetCode = readingPrefs.value.translationLanguage.code
            try {
                val entry = vocabularyRepository.lookup(chunkText, targetCode)
                _uiState.update { s ->
                    if (s.wordLookup?.word != chunkText) return@update s
                    s.copy(wordLookup = s.wordLookup.copy(
                        translation = entry?.definitions?.firstOrNull() ?: "—",
                        isLoading = false
                    ))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { s ->
                    if (s.wordLookup?.word != chunkText) return@update s
                    s.copy(wordLookup = s.wordLookup.copy(isLoading = false, error = e.message))
                }
            }
        }
    }

    fun dismissWordLookup() = _uiState.update { it.copy(wordLookup = null) }

    fun navigatePrevChapter() = navigateRelative(-1)
    fun navigateNextChapter() = navigateRelative(+1)

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildMediaItem(file: File, metadata: MediaMetadata): MediaItem =
        MediaItem.Builder().setUri(file.toUri()).setMediaMetadata(metadata).build()

    private fun generateAndPlay() {
        val chapter = _uiState.value.chapter ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(_uiState.value.chapterTitle)
            .setArtist(_uiState.value.bookTitle)
            .build()

        // Synthesis starts from the current chunk's first sentence — skip everything before it.
        // This eliminates the "wait 50 seconds for 50 sentences" cold-start problem.
        val startSentenceIndex = chapter.chunks
            .getOrNull(_uiState.value.currentChunkIndex)?.firstSentenceIndex ?: 0

        // Show progress immediately — don't wait for the first Generating event.
        _uiState.update { it.copy(audioGenerating = true, audioProgress = 0f) }

        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            try {
                var playbackStarted = false
                val effectiveSentences = chapter.sentences.size - startSentenceIndex
                val bufferSize = effectiveSentences.coerceIn(1, 5)
                val pendingItems = mutableListOf<MediaItem>()

                prepareChapterAudio(chapter, startSentenceIndex).collect { audioState ->
                    when (audioState) {
                        is ChapterAudioState.Generating -> {
                            // Progress relative to the synthesis window [startSentenceIndex..total).
                            if (!playbackStarted) {
                                val done  = (audioState.done  - startSentenceIndex).coerceAtLeast(0)
                                val total = (audioState.total - startSentenceIndex).coerceAtLeast(1)
                                _uiState.update { it.copy(audioProgress = done.toFloat() / total) }
                            }
                        }

                        is ChapterAudioState.ChunkReady -> {
                            // PerSentence path: files arrive in order from startSentenceIndex.
                            // pendingItems[k] = sentence (startSentenceIndex + k).
                            if (!playbackStarted) {
                                pendingItems += buildMediaItem(audioState.file, metadata)
                                // Re-read position — user may have scrolled during buffering.
                                val freshAbsStart = chapter.chunks
                                    .getOrNull(_uiState.value.currentChunkIndex)
                                    ?.firstSentenceIndex ?: startSentenceIndex
                                // Convert to a playlist-relative index.
                                val freshStart = (freshAbsStart - startSentenceIndex)
                                    .coerceIn(0, pendingItems.size - 1)
                                if (pendingItems.size >= freshStart + bufferSize) {
                                    currentPlaylistOffset = startSentenceIndex
                                    _player.setMediaItems(pendingItems, freshStart, 0L)
                                    _player.prepare(); _player.play()
                                    playbackStarted = true
                                    _uiState.update { it.copy(audioGenerating = false) }
                                    prefetchNextChapters()
                                    val deleteUpTo = (freshStart - LOCAL_TTS_KEEP_BACK)
                                        .coerceAtLeast(0)
                                    pendingItems.take(deleteUpTo).forEach { item ->
                                        item.localConfiguration?.uri?.path?.let {
                                            java.io.File(it).delete()
                                        }
                                    }
                                }
                            } else {
                                _player.addMediaItem(buildMediaItem(audioState.file, metadata))
                            }
                        }

                        is ChapterAudioState.Ready -> {
                            currentAudio = audioState.audio
                            currentPlaylistOffset = audioState.audio.playlistOffset
                            if (!playbackStarted) {
                                // Volcengine (SingleFile) or LOCAL if synthesis finished before buffering.
                                val items = buildPlaylistItems(audioState.audio, metadata)
                                val freshChunk = _uiState.value.currentChunkIndex

                                when (val src = audioState.audio.source) {
                                    is AudioSource.PerSentence -> {
                                        // Playlist-index seek: find playlist item for freshChunk.
                                        val offset = audioState.audio.playlistOffset
                                        val actualStart = audioState.audio.sentenceToChunk
                                            .indexOfFirst { it == freshChunk }
                                            .let { si -> if (si >= offset) si - offset else 0 }
                                            .coerceAtLeast(0)
                                        _player.setMediaItems(items, actualStart, 0L)
                                    }
                                    is AudioSource.SingleFile -> {
                                        // Single-item seek: find the start timestamp for freshChunk.
                                        // onMediaItemTransition won't fire during playback — chunk sync
                                        // is driven by the position-tracking coroutine instead.
                                        val startSentenceIdx = audioState.audio.sentenceToChunk
                                            .indexOfFirst { it == freshChunk }
                                            .coerceAtLeast(0)
                                        val startMs = src.timestamps.getOrNull(startSentenceIdx)?.startMs ?: 0L
                                        _player.setMediaItems(items, 0, startMs)
                                        startPositionTracking(audioState.audio)
                                    }
                                }

                                _player.prepare(); _player.play()
                                prefetchNextChapters()
                            }
                            _uiState.update { it.copy(audioGenerating = false) }
                        }

                        is ChapterAudioState.Error -> _uiState.update {
                            it.copy(audioGenerating = false, audioError = audioState.message)
                        }
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

    /**
     * Build ExoPlayer MediaItem list from a resolved [ChapterAudio].
     *
     * PerSentence: one item per sentence (LOCAL_ANDROID).
     * SingleFile:  one item for the entire chapter file (Volcengine).
     *   Seeking within the file is done via [seekTo(positionMs)] + position-tracking coroutine
     *   rather than ClippingConfiguration, which caused audible inter-sentence gaps.
     */
    private fun buildPlaylistItems(audio: ChapterAudio, metadata: MediaMetadata): List<MediaItem> =
        when (val src = audio.source) {
            is AudioSource.PerSentence -> src.files.map { buildMediaItem(it, metadata) }
            is AudioSource.SingleFile  -> listOf(buildMediaItem(src.audioFile, metadata))
        }

    /**
     * Polls ExoPlayer's current position every 150 ms and updates [currentChunkIndex] via the
     * [SentenceTimestamp] array. Used only for [AudioSource.SingleFile] (Volcengine), where the
     * entire chapter is one media item and [Player.Listener.onMediaItemTransition] never fires
     * during normal playback.
     *
     * Polling at 150 ms is well below the typical sentence length (~500–2000 ms), so the display
     * lags the audio by at most one poll cycle — imperceptible to the listener.
     */
    private fun startPositionTracking(audio: ChapterAudio) {
        val src = audio.source as? AudioSource.SingleFile ?: return
        positionTrackJob?.cancel()
        positionTrackJob = viewModelScope.launch {
            while (true) {
                val posMs = _player.currentPosition
                if (posMs >= 0) {
                    // Find the last sentence whose start time is ≤ posMs (linear scan, ~50–150 items).
                    val sentenceIdx = src.timestamps.indexOfLast { it.startMs <= posMs }
                        .coerceAtLeast(0)
                    val chunkIdx = audio.sentenceToChunk.getOrNull(sentenceIdx)
                    if (chunkIdx != null && chunkIdx != _uiState.value.currentChunkIndex) {
                        updateChunkIndex(chunkIdx)
                        val chapter = _uiState.value.chapter
                        if (chapter != null) {
                            progressRepository.savePosition(
                                ReadingPosition(bookId, chapter.id, chunkIdx)
                            )
                        }
                    }
                }
                delay(150L)
            }
        }
    }

    private fun prefetchNextChapters() {
        // LOCAL_ANDROID synthesis is on-demand (real-time); no pre-download needed.
        // VOLCENGINE audio is user-managed from the chapter list screen.
        // → no-op kept as a call-site placeholder in case prefetch is added later.
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
        positionTrackJob?.cancel()
        _player.stop()
        _player.clearMediaItems()
        deleteAllLocalTtsFiles()   // clean up any remaining temp files from the previous chapter
        currentAudio = null
        currentPlaylistOffset = 0
        _uiState.update {
            it.copy(isLoading = true, error = null, isPlaying = false, audioGenerating = false)
        }
        val chunkSize = settingsRepository.getReadingPreferences().chunkSize
        runCatching { epubRepository.loadChapter(position.bookId, position.chapterId, chunkSize) }
            .onSuccess { chapter ->
                _uiState.update {
                    it.copy(
                        bookTitle = book.title,
                        chapterTitle = chapter.title,
                        chapter = chapter,
                        currentChunkIndex = position.indexInChapter,
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

    // ── Audio state reset ─────────────────────────────────────────────────────

    /**
     * Cancel synthesis, stop the player, and clear all audio state.
     * Does NOT start new synthesis — the user must press play explicitly.
     *
     * Called when:
     *  - User seeks past the LOCAL_ANDROID sliding window (file deleted)
     *  - ExoPlayer reports ERROR_CODE_IO_FILE_NOT_FOUND (same root cause, caught reactively)
     */
    private fun stopAndClearAudio() {
        audioJob?.cancel()
        positionTrackJob?.cancel()
        _player.stop()
        _player.clearMediaItems()
        deleteAllLocalTtsFiles()
        currentAudio = null
        currentPlaylistOffset = 0
        _uiState.update { it.copy(isPlaying = false, audioGenerating = false) }
    }

    // ── Local TTS file lifecycle ──────────────────────────────────────────────

    /** Delete one temp WAV file by playlist index (no-op for SingleFile or if already deleted). */
    private fun deleteLocalTtsFile(playlistIndex: Int) {
        (currentAudio?.source as? AudioSource.PerSentence)
            ?.files?.getOrNull(playlistIndex)?.delete()
    }

    /** Delete all remaining temp WAV files for the current chapter. */
    private fun deleteAllLocalTtsFiles() {
        (currentAudio?.source as? AudioSource.PerSentence)
            ?.files?.forEach { it.delete() }
    }

    /**
     * Playlist index → display chunk index.
     * sentenceIndex = playlistIndex + offset, where offset = [ChapterAudio.playlistOffset]
     * (or [currentPlaylistOffset] during the streaming phase before [currentAudio] is resolved).
     */
    private fun playlistIndexToChunk(playlistIndex: Int): Int? {
        val chapter = _uiState.value.chapter ?: return null
        val offset = currentAudio?.playlistOffset ?: currentPlaylistOffset
        val sentenceIndex = playlistIndex + offset
        return currentAudio?.sentenceToChunk?.getOrNull(sentenceIndex)
            ?: chapter.sentences.getOrNull(sentenceIndex)?.chunkIndex
    }

    private fun updateChunkIndex(index: Int) {
        _uiState.update { it.copy(
            currentChunkIndex = index,
            wordLookup = if (index != it.currentChunkIndex) null else it.wordLookup
        )}
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
        lookupJob?.cancel()
        positionTrackJob?.cancel()
        deleteAllLocalTtsFiles()
        if (::_player.isInitialized) {
            _player.stop(); _player.clearMediaItems(); _player.release()
        }
    }
}
