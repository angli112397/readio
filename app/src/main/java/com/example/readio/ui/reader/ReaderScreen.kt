package com.example.readio.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

    LaunchedEffect(state.audioError) {
        state.audioError?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            viewModel.dismissAudioError()
        }
    }

    // Chapter progress — fraction of chunks consumed; 0 when chapter not loaded.
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
            // Thin chapter-progress indicator sits flush above the bottom bar —
            // no padding so the line feels like it belongs to the bar, not the content.
            Column {
                LinearProgressIndicator(
                    progress = { chapterProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                PlayerBar(
                    isPlaying = state.isPlaying,
                    audioGenerating = state.audioGenerating,
                    audioProgress = state.audioProgress,
                    hasPrev = state.hasPrevChapter,
                    hasNext = state.hasNextChapter,
                    speechRate = state.speechRate,
                    onPlayPause = viewModel::onPlayPause,
                    onPrev = viewModel::navigatePrevChapter,
                    onNext = viewModel::navigateNextChapter,
                    onSpeedChange = viewModel::onSpeechRateStep
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                state.error != null -> Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> ChunkWheel(
                    chunks = state.chapter?.chunks ?: emptyList(),
                    currentIndex = state.currentChunkIndex,
                    onCenterChanged = viewModel::onChunkTap,
                    fontSize = prefs.fontSize.sp,
                    lineHeightMultiplier = prefs.lineHeightMultiplier,
                    onTranslateTap = viewModel::onTranslateTap,
                    onScrollStarted = viewModel::onScrollStarted,
                    isScrollEnabled = !state.audioGenerating,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Translation card — overlay at top, slides down from TopAppBar.
            // Tap the card (or tap the center chunk again) to dismiss.
            AnimatedVisibility(
                visible = state.wordLookup != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                val lookup = state.wordLookup ?: return@AnimatedVisibility
                WordLookupCard(
                    lookup = lookup,
                    onDismiss = viewModel::dismissWordLookup
                )
            }
        }
    }
}

// ── Translation card ──────────────────────────────────────────────────────────

@Composable
private fun WordLookupCard(lookup: WordLookup, onDismiss: () -> Unit) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Original text — muted, truncated to two lines
            Text(
                text = lookup.word,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            when {
                lookup.isLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.5.dp
                    )
                    Text(
                        "翻译中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                lookup.error != null -> Text(
                    "翻译失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                lookup.translation != null -> Text(
                    lookup.translation,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ── Player bar ────────────────────────────────────────────────────────────────

@Composable
private fun PlayerBar(
    isPlaying: Boolean,
    audioGenerating: Boolean,
    audioProgress: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    speechRate: Float,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeedChange: () -> Unit,
) {
    BottomAppBar(contentPadding = PaddingValues(horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ← Prev chapter
            IconButton(onClick = onPrev, enabled = hasPrev && !audioGenerating) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上一章"
                )
            }

            // Speed label — tap to cycle through presets
            TextButton(
                onClick = onSpeedChange,
                enabled = !audioGenerating,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = formatSpeechRate(speechRate),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Play / pause / generating indicator
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (audioGenerating) {
                    CircularProgressIndicator(
                        progress = { audioProgress },
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                    // Show percentage only after at least 1 % is done,
                    // so the initial "0%" flash doesn't appear.
                    if (audioProgress > 0.01f) {
                        Text(
                            text = "${(audioProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause
                                          else          Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Next chapter →
            IconButton(onClick = onNext, enabled = hasNext && !audioGenerating) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下一章"
                )
            }
        }
    }
}

/**
 * Format a speech-rate float for compact display.
 * 1.0 → "1×"   0.75 → "0.75×"   1.25 → "1.25×"   2.0 → "2×"
 */
private fun formatSpeechRate(rate: Float): String {
    val s = "%.2f".format(rate).trimEnd('0').trimEnd('.')
    return "$s×"
}
