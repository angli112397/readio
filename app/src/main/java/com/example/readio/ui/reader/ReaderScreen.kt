package com.example.readio.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.ChapterAudioStatus

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onChapterList: (bookId: String) -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.readingPrefs.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Confirmation dialogs — shown on long-press of the player-bar button.
    var showCancelDialog     by remember { mutableStateOf(false) }
    var showDeleteDialog     by remember { mutableStateOf(false) }
    var showClearErrorDialog by remember { mutableStateOf(false) }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title   = { Text("取消合成？") },
            text    = { Text("正在进行的合成任务将被取消，服务器任务记录也会被清除。") },
            confirmButton = {
                TextButton(onClick = { showCancelDialog = false; viewModel.onClearChapterAudio() }) {
                    Text("取消任务", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("保留") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title   = { Text("删除音频？") },
            text    = { Text("「${state.chapterTitle}」的缓存音频将被删除，下次播放需重新合成。") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; viewModel.onClearChapterAudio() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showClearErrorDialog) {
        AlertDialog(
            onDismissRequest = { showClearErrorDialog = false },
            title   = { Text("清除失败记录？") },
            text    = { Text("将删除本地失败记录及服务器任务，之后可重新提交合成。") },
            confirmButton = {
                TextButton(onClick = { showClearErrorDialog = false; viewModel.onClearChapterAudio() }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearErrorDialog = false }) { Text("取消") }
            }
        )
    }

    LaunchedEffect(state.audioError) {
        state.audioError?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.dismissAudioError()
        }
    }

    val chapterProgress by remember(state.currentChunkIndex, state.chapter) {
        derivedStateOf {
            val total = (state.chapter?.chunks?.size ?: 1) - 1
            if (total > 0) state.currentChunkIndex.toFloat() / total else 0f
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.bookTitle, style = MaterialTheme.typography.titleSmall)
                        if (state.chapterTitle.isNotEmpty()) {
                            Text(
                                state.chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onChapterList(viewModel.bookId) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "章节列表")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                LinearProgressIndicator(
                    progress     = { chapterProgress },
                    modifier     = Modifier.fillMaxWidth().height(2.dp),
                    color        = MaterialTheme.colorScheme.primary,
                    trackColor   = MaterialTheme.colorScheme.surfaceVariant
                )
                PlayerBar(
                    isPlaying        = state.isPlaying,
                    audioGenerating  = state.audioGenerating,
                    audioProgress    = state.audioProgress,
                    batchAudioStatus = state.batchAudioStatus,
                    onPlayPause      = viewModel::onPlayPause,
                    onCacheChapter   = viewModel::onCacheChapter,
                    onShowStatus     = viewModel::onShowBatchStatusCard,
                    onLongPressCache = { showCancelDialog = true },
                    onLongPressPlay  = { showDeleteDialog = true },
                    onLongPressError = { showClearErrorDialog = true },
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading     -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(
                    text     = state.error!!,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> ChunkWheel(
                    chunks               = state.chapter?.chunks ?: emptyList(),
                    currentIndex         = state.currentChunkIndex,
                    onCenterChanged      = viewModel::onChunkTap,
                    fontSize             = prefs.fontSize.sp,
                    lineHeightMultiplier = prefs.lineHeightMultiplier,
                    onTranslateTap       = viewModel::onTranslateTap,
                    onScrollStarted      = viewModel::onScrollStarted,
                    isScrollEnabled      = !state.audioGenerating,
                    modifier             = Modifier.fillMaxSize()
                )
            }

            // Translation card — slides down from the top bar. Dismissed by tapping.
            AnimatedVisibility(
                visible  = state.wordLookup != null,
                enter    = slideInVertically { -it } + fadeIn(),
                exit     = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                WordLookupCard(state.wordLookup ?: return@AnimatedVisibility, viewModel::dismissWordLookup)
            }

            // Batch status card — same visual slot as the translation card.
            // Shown when the user taps the cache/hourglass/progress button.
            AnimatedVisibility(
                visible  = state.showBatchStatusCard && state.batchAudioStatus != null,
                enter    = slideInVertically { -it } + fadeIn(),
                exit     = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                BatchStatusCard(state.batchAudioStatus ?: return@AnimatedVisibility, viewModel::dismissBatchStatusCard)
            }
        }
    }
}

// ── Translation card ──────────────────────────────────────────────────────────

@Composable
private fun WordLookupCard(lookup: WordLookup, onDismiss: () -> Unit) {
    Surface(
        tonalElevation   = 8.dp,
        shadowElevation  = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication            = null,
                interactionSource     = remember { MutableInteractionSource() },
                onClick               = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text      = lookup.word,
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines  = 2,
                overflow  = TextOverflow.Ellipsis
            )
            when {
                lookup.isLoading -> Row(
                    verticalAlignment       = Alignment.CenterVertically,
                    horizontalArrangement   = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
                    Text("翻译中…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lookup.error != null -> Text(
                    "翻译失败", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                lookup.translation != null -> Text(lookup.translation, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ── Batch status card ─────────────────────────────────────────────────────────

@Composable
private fun BatchStatusCard(status: ChapterAudioStatus, onDismiss: () -> Unit) {
    Surface(
        tonalElevation   = 8.dp,
        shadowElevation  = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick           = onDismiss
            )
    ) {
        Row(
            modifier              = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (status) {
                is ChapterAudioStatus.Downloading -> {
                    val det = status.progress in 0.01f..0.99f
                    if (det) {
                        CircularProgressIndicator(progress = { status.progress },
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Column {
                        Text(
                            text  = status.label.ifBlank {
                                if (det) "下载中 ${(status.progress * 100).toInt()}%" else "处理中…"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "点击关闭，长按按钮取消",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is ChapterAudioStatus.HasTaskId -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Column {
                        Text("任务合成中，服务器正在处理…", style = MaterialTheme.typography.bodyMedium)
                        Text("再次点击沙漏图标查询最新状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ChapterAudioStatus.Error -> {
                    Icon(Icons.Default.Warning, contentDescription = null,
                        tint     = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp))
                    Text(
                        "合成失败：${status.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> { /* card is not shown for other states */ }
            }
        }
    }
}

// ── Player bar ────────────────────────────────────────────────────────────────

/**
 * Unified bottom player control. Renders a single icon-button whose appearance and
 * behaviour depend on the current [batchAudioStatus]:
 *
 * - **null** (realtime TTS): classic play ↔ pause (or progress ring during synthesis).
 * - **NotDownloaded**: cloud-download button — tap to cache.
 * - **Error**: cloud-download button (error tint) — tap to retry, long-press to clear failed task.
 * - **HasTaskId**: hourglass — tap to re-poll, long-press to cancel.
 * - **Downloading**: progress ring — tap to show status card, long-press to cancel.
 * - **Downloaded**: play ↔ pause — long-press to delete cached audio.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerBar(
    isPlaying:        Boolean,
    audioGenerating:  Boolean,
    audioProgress:    Float,
    batchAudioStatus: ChapterAudioStatus?,
    onPlayPause:      () -> Unit,
    onCacheChapter:   () -> Unit,
    onShowStatus:     () -> Unit,
    onLongPressCache: () -> Unit,
    onLongPressPlay:  () -> Unit,
    onLongPressError: () -> Unit,
) {
    BottomAppBar(contentPadding = PaddingValues(horizontal = 4.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when (batchAudioStatus) {
                    // ── Realtime or no batch status ───────────────────────────
                    null, ChapterAudioStatus.NotApplicable -> RealtimeButton(
                        isPlaying, audioGenerating, audioProgress, onPlayPause
                    )

                    // ── Not cached ────────────────────────────────────────────
                    ChapterAudioStatus.NotDownloaded -> {
                        IconButton(onClick = onCacheChapter, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = "缓存章节音频",
                                modifier = Modifier.size(30.dp),
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ── Synthesis failed — tap to retry, long-press to clear ──
                    is ChapterAudioStatus.Error -> CombinedButton(
                        onClick     = onCacheChapter,
                        onLongClick = onLongPressError
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "重试合成，长按清除失败任务",
                            modifier = Modifier.size(30.dp),
                            tint     = MaterialTheme.colorScheme.error
                        )
                    }

                    // ── Pending server result ─────────────────────────────────
                    is ChapterAudioStatus.HasTaskId -> CombinedButton(
                        onClick     = onCacheChapter,
                        onLongClick = onLongPressCache
                    ) {
                        Icon(Icons.Default.HourglassEmpty,
                            contentDescription = "合成中，点击查询状态",
                            modifier = Modifier.size(28.dp),
                            tint     = MaterialTheme.colorScheme.primary)
                    }

                    // ── Actively downloading ──────────────────────────────────
                    is ChapterAudioStatus.Downloading -> CombinedButton(
                        onClick     = onShowStatus,
                        onLongClick = onLongPressCache
                    ) {
                        val p = batchAudioStatus.progress
                        if (p in 0.01f..0.99f) {
                            CircularProgressIndicator(
                                progress = { p }, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        }
                    }

                    // ── Cached — play / pause ─────────────────────────────────
                    ChapterAudioStatus.Downloaded -> CombinedButton(
                        onClick     = onPlayPause,
                        onLongClick = onLongPressPlay
                    ) {
                        Icon(
                            imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            modifier           = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Realtime TTS button: synthesis progress ring → play/pause. */
@Composable
private fun RealtimeButton(
    isPlaying: Boolean,
    audioGenerating: Boolean,
    audioProgress: Float,
    onPlayPause: () -> Unit,
) {
    if (audioGenerating) {
        CircularProgressIndicator(
            progress   = { audioProgress },
            modifier   = Modifier.size(40.dp),
            strokeWidth = 3.dp
        )
        if (audioProgress > 0.01f) {
            Text("${(audioProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }
    } else {
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                modifier           = Modifier.size(32.dp)
            )
        }
    }
}

/** Tap + long-press wrapper that fills the 48 dp hit-target. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CombinedButton(
    onClick:     () -> Unit,
    onLongClick: () -> Unit,
    content:     @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier          = Modifier.size(48.dp).combinedClickable(
            onClick     = onClick,
            onLongClick = onLongClick
        ),
        contentAlignment  = Alignment.Center,
        content           = content
    )
}
