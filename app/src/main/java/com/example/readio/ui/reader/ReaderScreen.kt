package com.example.readio.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onSettingsOpen: () -> Unit,
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
                    IconButton(onClick = onSettingsOpen) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            PlayerBar(
                isPlaying = state.isPlaying,
                audioGenerating = state.audioGenerating,
                audioProgress = state.audioProgress,
                hasPrev = state.hasPrevChapter,
                hasNext = state.hasNextChapter,
                onPlayPause = viewModel::onPlayPause,
                onPrev = viewModel::navigatePrevChapter,
                onNext = viewModel::navigateNextChapter
            )
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
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Translation card — overlay at top, slides down from TopAppBar
            AnimatedVisibility(
                visible = state.wordLookup != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                val lookup = state.wordLookup ?: return@AnimatedVisibility
                WordLookupCard(lookup = lookup)
            }
        }
    }
}

@Composable
private fun WordLookupCard(lookup: WordLookup) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .heightIn(max = 80.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                lookup.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                lookup.error != null -> Text(
                    "翻译失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                lookup.translation != null -> Text(
                    lookup.translation,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun PlayerBar(
    isPlaying: Boolean,
    audioGenerating: Boolean,
    audioProgress: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = hasPrev && !audioGenerating) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一章")
            }

            Box(contentAlignment = Alignment.Center) {
                if (audioGenerating) {
                    CircularProgressIndicator(
                        progress = { audioProgress },
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "${(audioProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放"
                        )
                    }
                }
            }

            IconButton(onClick = onNext, enabled = hasNext && !audioGenerating) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一章")
            }
        }
    }
}
