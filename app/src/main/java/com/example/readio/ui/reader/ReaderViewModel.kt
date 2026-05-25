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
    val isLoading: Boolean = true,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val audioGenerating: Boolean = false,
    val audioProgress: Float = 0f,
    val audioError: String? = null,
    val wordLookup: WordLookup? = null,
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
    /** Polls ExoPlayer position every 150 ms to drive chunk sync for SingleFile (batch TTS). */
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

            // Apply speech rate from settings to ExoPlayer (no UI mirror needed — rate lives in settings).
            settingsRepository.observeTtsConfig()
                .onEach { config -> _player.setPlaybackSpeed(config.speechRate) }
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
     */
    private fun buildTranslationContext(chapter: Chapter, chunkIndex: Int): String {
        val allAtoms = chapter.sentences
        if (allAtoms.isEmpty()) return chapter.chunks.getOrNull(chunkIndex)?.text ?: ""

        val chunkAtoms = allAtoms.filter { it.chunkIndex == chunkIndex }
        if (chunkAtoms.isEmpty()) return chapter.chunks.getOrNull(chunkIndex)?.text ?: ""

        val firstIdx = chunkAtoms.first().indexInChapter
        val lastIdx  = chunkAtoms.last().indexInChapter

        var start = firstIdx
        while (start > 0 && !isSentenceTerminal(allAtoms[start - 1].text)) {
            start--
        }

        var end = lastIdx
        while (end < allAtoms.lastIndex && !isSentenceTerminal(allAtoms[end].text)) {
            end++
        }

        return allAtoms.subList(start, end + 1).joinToString("") { it.text }
    }

    private fun isSentenceTerminal(text: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        return last in "。！？…!?."
    }

    fun dismissWordLookup() = _uiState.update { it.copy(wordLookup = null) }

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

        val startChunkIndex = _uiState.value.currentChunkIndex
        val startTrueSentenceIdx = currentSentenceToChunk
            .indexOfFirst { it >= startChunkIndex }
            .let { if (it < 0) (currentSentenceToChunk.size - 1).coerceAtLeast(0) else it }

        _uiState.update { it.copy(audioGenerating = true, audioProgress = 0f) }

        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            try {
                val config = effectiveTtsConfig()
                var playbackStarted = false
                val effectiveSentences = currentSentenceToChunk.size - startTrueSentenceIdx
                val bufferSize = effectiveSentences.coerceIn(1, 3)
                val pendingItems = mutableListOf<MediaItem>()

                prepareChapterAudio(chapter, startChunkIndex, config).collect { audioState ->
                    when (audioState) {
                        is ChapterAudioState.Generating -> {
                            if (!playbackStarted) {
                                _uiState.update {
                                    it.copy(audioProgress =
                                        audioState.done.toFloat() / audioState.total.coerceAtLeast(1))
                                }
                            }
                        }

                        is ChapterAudioState.ChunkReady -> {
                            if (!playbackStarted) {
                                pendingItems += buildMediaItem(audioState.file, metadata)
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
                                val items = buildPlaylistItems(audioState.audio, metadata)
                                val freshChunk = _uiState.value.currentChunkIndex

                                when (val src = audioState.audio.source) {
                                    is AudioSource.PerSentence -> {
                                        val stc = audioState.audio.sentenceToChunk
                                        val offset = audioState.audio.playlistOffset
                                        val si = stc.indexOfFirst { it >= freshChunk }
                                            .let { if (it < 0) stc.lastIndex else it }
                                        val actualStart = (si - offset)
                                            .coerceIn(0, items.size - 1)
                                        _player.setMediaItems(items, actualStart, 0L)
                                    }
                                    is AudioSource.SingleFile -> {
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

    private fun buildPlaylistItems(audio: ChapterAudio, metadata: MediaMetadata): List<MediaItem> =
        when (val src = audio.source) {
            is AudioSource.PerSentence -> src.files.map { buildMediaItem(it, metadata) }
            is AudioSource.SingleFile  -> listOf(buildMediaItem(src.audioFile, metadata))
        }

    private fun startPositionTracking(audio: ChapterAudio) {
        val src = audio.source as? AudioSource.SingleFile ?: return
        positionTrackJob?.cancel()
        positionTrackJob = viewModelScope.launch {
            while (true) {
                val posMs = _player.currentPosition
                if (posMs >= 0) {
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
        // Batch TTS (VOLCENGINE / FISH_SPEECH) audio is user-managed from the chapter list screen.
        // → no-op kept as a call-site placeholder in case prefetch is added later.
    }

    private suspend fun loadChapterAt(book: EpubBook, position: ReadingPosition) {
        audioJob?.cancel()
        prefetchJob?.cancel()
        positionTrackJob?.cancel()
        _player.stop()
        _player.clearMediaItems()
        deleteAllLocalTtsFiles()
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
                        bookTitle         = book.title,
                        chapterTitle      = chapter.title,
                        chapter           = chapter,
                        currentChunkIndex = position.indexInChapter,
                        isLoading         = false
                    )
                }
                progressRepository.savePosition(position)
            }
            .onFailure { e ->
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
    }

    // ── Audio state reset ─────────────────────────────────────────────────────

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

    private fun deleteLocalTtsFile(playlistIndex: Int) {
        (currentAudio?.source as? AudioSource.PerSentence)
            ?.files?.getOrNull(playlistIndex)?.delete()
    }

    private fun deleteAllLocalTtsFiles() {
        (currentAudio?.source as? AudioSource.PerSentence)
            ?.files?.forEach { it.delete() }
    }

    private fun playlistIndexToChunk(playlistIndex: Int): Int? {
        val offset = currentAudio?.playlistOffset ?: currentPlaylistOffset
        val index  = playlistIndex + offset
        return currentAudio?.sentenceToChunk?.getOrNull(index)
            ?: currentSentenceToChunk.getOrNull(index)
    }

    private fun computeSentenceToChunk(chapter: Chapter): List<Int> {
        val result = mutableListOf<Int>()
        var firstChunk = -1
        for (atom in chapter.sentences) {
            if (cleanForTts(atom.text).isBlank()) continue
            if (firstChunk < 0) firstChunk = atom.chunkIndex
            val last = atom.text.trimEnd().lastOrNull()
            if (last != null && last in "。！？…!?.") {
                result += firstChunk
                firstChunk = -1
            }
        }
        if (firstChunk >= 0) result += firstChunk
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
