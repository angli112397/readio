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
import com.example.readio.data.audio.cleanForTts
import com.example.readio.domain.model.AudioSource
import com.example.readio.domain.model.Chapter
import com.example.readio.domain.model.ChapterAudio
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.TtsConfig
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
    val wordLookup: WordLookup? = null,
    /** Current TTS playback speed — mirrored from TtsConfig for PlayerBar display. */
    val speechRate: Float = 1.0f
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

    /**
     * True-sentence → chunk mapping, mirrored from [ChapterAudio.sentenceToChunk] but available
     * during the streaming phase before [currentAudio] is set.
     * `currentSentenceToChunk[k]` = chunk index of the first atom of true sentence k.
     * Set from [computeSentenceToChunk] at the start of [generateAndPlay]; reset on stop/load.
     */
    private var currentSentenceToChunk: List<Int> = emptyList()

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
                .onEach { config ->
                    _player.setPlaybackSpeed(config.speechRate)
                    _uiState.update { it.copy(speechRate = config.speechRate) }
                }
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
        val state   = _uiState.value
        val chapter = state.chapter ?: return

        // Build the full logical sentence(s) that overlap with the current chunk.
        // This expands across chunk boundaries so comma-split fragments that belong to
        // the same punctuation-sentence are included, giving the engine better context.
        val contextText = buildTranslationContext(chapter, state.currentChunkIndex)
        if (contextText.isBlank()) return

        // Second tap on the same content dismisses the card.
        if (state.wordLookup?.word == contextText && !state.wordLookup.isLoading) {
            dismissWordLookup(); return
        }

        lookupJob?.cancel()
        _uiState.update { it.copy(wordLookup = WordLookup(word = contextText)) }
        lookupJob = viewModelScope.launch {
            val targetCode = readingPrefs.value.translationLanguage.code
            try {
                val entry = vocabularyRepository.lookup(contextText, targetCode)
                _uiState.update { s ->
                    if (s.wordLookup?.word != contextText) return@update s
                    s.copy(wordLookup = s.wordLookup.copy(
                        translation = entry?.definitions?.firstOrNull() ?: "—",
                        isLoading = false
                    ))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { s ->
                    if (s.wordLookup?.word != contextText) return@update s
                    s.copy(wordLookup = s.wordLookup.copy(isLoading = false, error = e.message))
                }
            }
        }
    }

    /**
     * Assemble the full logical sentence(s) that overlap with [chunkIndex].
     *
     * The TextChunker's two-pass algorithm (expand → bin-pack) can split a single
     * punctuation-sentence across two adjacent chunks when a comma-split fragment
     * doesn't fit in the current chunk and overflows into the next.
     *
     * Strategy: take all sentence-atoms belonging to [chunkIndex], then walk outward
     * until we reach atoms that end with sentence-terminal punctuation (。！？…!?.)
     * in both directions, including all atoms in between.
     *
     * Example:
     *   Atom 5 (chunk 1): "因为他很努力，"       ← comma-split, not terminal
     *   Atom 6 (chunk 2): "所以他终于成功了。"   ← terminal  ← current chunk starts here
     *   Atom 7 (chunk 2): "下一句话。"
     *
     * Translating chunk 2 normally gives only "所以他终于成功了。下一句话。"
     * With context expansion: atoms 5–7 → "因为他很努力，所以他终于成功了。下一句话。"
     */
    private fun buildTranslationContext(chapter: Chapter, chunkIndex: Int): String {
        val allAtoms = chapter.sentences
        if (allAtoms.isEmpty()) return chapter.chunks.getOrNull(chunkIndex)?.text ?: ""

        val chunkAtoms = allAtoms.filter { it.chunkIndex == chunkIndex }
        if (chunkAtoms.isEmpty()) return chapter.chunks.getOrNull(chunkIndex)?.text ?: ""

        val firstIdx = chunkAtoms.first().indexInChapter
        val lastIdx  = chunkAtoms.last().indexInChapter

        // Walk backward: include atoms that form the beginning of the current sentence.
        // Stop when the atom before [start] already terminates a sentence (or at index 0).
        var start = firstIdx
        while (start > 0 && !isSentenceTerminal(allAtoms[start - 1].text)) {
            start--
        }

        // Walk forward: extend to the end of the current sentence.
        // Stop when the atom at [end] itself terminates a sentence (or at last index).
        var end = lastIdx
        while (end < allAtoms.lastIndex && !isSentenceTerminal(allAtoms[end].text)) {
            end++
        }

        return allAtoms.subList(start, end + 1).joinToString("") { it.text }
    }

    /**
     * True if [text] ends with a sentence-terminal punctuation mark.
     * Used by [buildTranslationContext] to detect logical sentence boundaries.
     */
    private fun isSentenceTerminal(text: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        return last in "。！？…!?."
    }

    fun dismissWordLookup() = _uiState.update { it.copy(wordLookup = null) }

    /**
     * Cycle to the next preset playback speed.
     * Presets: 0.75 → 1.0 → 1.25 → 1.5 → 2.0 → (wrap) 0.75
     * The existing TtsConfig observer applies the change to ExoPlayer automatically.
     */
    fun onSpeechRateStep() {
        viewModelScope.launch {
            val config = settingsRepository.getTtsConfig()
            val steps = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            val currentIdx = steps.indexOfFirst { it >= config.speechRate - 0.01f }
                .takeIf { it >= 0 } ?: 0
            val nextRate = steps[(currentIdx + 1) % steps.size]
            settingsRepository.saveTtsConfig(config.copy(speechRate = nextRate))
        }
    }

    fun navigatePrevChapter() = navigateRelative(-1)
    fun navigateNextChapter() = navigateRelative(+1)

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildMediaItem(file: File, metadata: MediaMetadata): MediaItem =
        MediaItem.Builder().setUri(file.toUri()).setMediaMetadata(metadata).build()

    /**
     * Returns the TTS config to use for the current book:
     * the global config with the book's per-book provider/voice override applied (if set).
     */
    private suspend fun effectiveTtsConfig(): TtsConfig {
        val global = settingsRepository.getTtsConfig()
        return global.applyBookOverride(currentBook?.ttsProvider, currentBook?.ttsVoice)
    }

    private fun generateAndPlay() {
        val chapter = _uiState.value.chapter ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(_uiState.value.chapterTitle)
            .setArtist(_uiState.value.bookTitle)
            .build()

        // Build the true-sentence mapping NOW so it's available for the streaming-phase
        // fallback in playlistIndexToChunk() before currentAudio is resolved.
        currentSentenceToChunk = computeSentenceToChunk(chapter)

        // Realtime TTS: synthesis starts at the current chunk's first true sentence.
        // Batch TTS (Volcengine / SingleFile): startChunkIndex is forwarded but not used.
        val startChunkIndex = _uiState.value.currentChunkIndex
        // First true sentence at or after the starting chunk (may skip a cross-chunk tail).
        val startTrueSentenceIdx = currentSentenceToChunk
            .indexOfFirst { it >= startChunkIndex }
            .let { if (it < 0) (currentSentenceToChunk.size - 1).coerceAtLeast(0) else it }

        // Show progress immediately — don't wait for the first Generating event.
        _uiState.update { it.copy(audioGenerating = true, audioProgress = 0f) }

        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            try {
                val config = effectiveTtsConfig()
                var playbackStarted = false
                val effectiveSentences = currentSentenceToChunk.size - startTrueSentenceIdx
                // Buffer 3 true sentences before starting playback.  Sentences are shorter than
                // chunks (~30–60 chars vs ~150), so synthesis is faster and 3 is a good balance.
                val bufferSize = effectiveSentences.coerceIn(1, 3)
                val pendingItems = mutableListOf<MediaItem>()

                prepareChapterAudio(chapter, startChunkIndex, config).collect { audioState ->
                    when (audioState) {
                        is ChapterAudioState.Generating -> {
                            // AudioRepositoryImpl emits relative progress (done / totalFromStart).
                            if (!playbackStarted) {
                                _uiState.update {
                                    it.copy(audioProgress =
                                        audioState.done.toFloat() / audioState.total.coerceAtLeast(1))
                                }
                            }
                        }

                        is ChapterAudioState.ChunkReady -> {
                            // Realtime path: one file per true sentence, arriving in synthesis order.
                            // pendingItems[k] = true sentence (startTrueSentenceIdx + k).
                            if (!playbackStarted) {
                                pendingItems += buildMediaItem(audioState.file, metadata)
                                // Re-read current chunk — user may have scrolled during buffering.
                                val freshChunk = _uiState.value.currentChunkIndex
                                val freshSentIdx = currentSentenceToChunk
                                    .indexOfFirst { it >= freshChunk }
                                    .let { if (it < 0) startTrueSentenceIdx else it }
                                val freshStart = (freshSentIdx - startTrueSentenceIdx)
                                    .coerceIn(0, pendingItems.size - 1)
                                if (pendingItems.size >= freshStart + bufferSize) {
                                    currentPlaylistOffset = startTrueSentenceIdx
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
                                // Volcengine (SingleFile) or if synthesis finished before buffering.
                                val items = buildPlaylistItems(audioState.audio, metadata)
                                val freshChunk = _uiState.value.currentChunkIndex

                                when (val src = audioState.audio.source) {
                                    is AudioSource.PerSentence -> {
                                        // Find first true sentence at or after freshChunk, then
                                        // convert to playlist-relative index.
                                        val stc = audioState.audio.sentenceToChunk
                                        val offset = audioState.audio.playlistOffset
                                        val si = stc.indexOfFirst { it >= freshChunk }
                                            .let { if (it < 0) stc.lastIndex else it }
                                        val actualStart = (si - offset)
                                            .coerceIn(0, items.size - 1)
                                        _player.setMediaItems(items, actualStart, 0L)
                                    }
                                    is AudioSource.SingleFile -> {
                                        // Single-item seek: find the start timestamp for freshChunk.
                                        // onMediaItemTransition won't fire during playback — chunk sync
                                        // is driven by the position-tracking coroutine instead.
                                        val startSentenceIdx = audioState.audio.sentenceToChunk
                                            .indexOfFirst { it == freshChunk }
                                            .coerceAtLeast(0)
                                        val startMs = src.timings.getOrNull(startSentenceIdx)?.startMs ?: 0L
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
                    // Find the last atom whose start time is ≤ posMs (linear scan, ~50–150 items).
                    val sentenceIdx = src.timings.indexOfLast { it.startMs <= posMs }
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
        currentSentenceToChunk = emptyList()
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
        currentSentenceToChunk = emptyList()
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
     *
     * For realtime (PerSentence) audio, each playlist item = one true sentence.
     * `index = playlistIndex + offset` = absolute true-sentence index.
     * `sentenceToChunk[index]` = chunk of that sentence's first atom.
     *
     * Primary path (after [currentAudio] is set): uses [ChapterAudio.sentenceToChunk].
     * Streaming-phase fallback (before [currentAudio] is resolved): uses [currentSentenceToChunk],
     * which is computed at synthesis start and stays valid throughout streaming.
     *
     * For batch (SingleFile) audio, [currentAudio] is always set before the first
     * [onMediaItemTransition], so the fallback is never reached on that path.
     */
    private fun playlistIndexToChunk(playlistIndex: Int): Int? {
        val offset = currentAudio?.playlistOffset ?: currentPlaylistOffset
        val index  = playlistIndex + offset   // absolute true-sentence index
        return currentAudio?.sentenceToChunk?.getOrNull(index)
            ?: currentSentenceToChunk.getOrNull(index)
    }

    /**
     * Pre-computes the true-sentence → chunk mapping from the chapter's sentence atoms.
     *
     * Mirrors the logic in [AudioRepositoryImpl.buildTrueSentences]: groups consecutive atoms
     * until one ends with terminal punctuation (。！？…!?.), then records the chunk index of
     * the first atom in each group.
     *
     * The result is stored in [currentSentenceToChunk] so [playlistIndexToChunk] can resolve
     * transitions during the streaming phase before [currentAudio] is set.
     */
    private fun computeSentenceToChunk(chapter: Chapter): List<Int> {
        val result = mutableListOf<Int>()
        var firstChunk = -1
        for (atom in chapter.sentences) {
            // Mirror the skip logic in AudioRepositoryImpl.buildTrueSentences():
            // noise-only atoms (URLs, lone brackets) must not claim firstChunk.
            if (cleanForTts(atom.text).isBlank()) continue
            if (firstChunk < 0) firstChunk = atom.chunkIndex
            val last = atom.text.trimEnd().lastOrNull()
            if (last != null && last in "。！？…!?.") {
                result += firstChunk
                firstChunk = -1
            }
        }
        if (firstChunk >= 0) result += firstChunk   // trailing non-terminal atoms
        return result
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
