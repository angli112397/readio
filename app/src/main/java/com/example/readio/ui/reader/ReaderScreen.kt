package com.example.readio.ui.reader

import androidx.compose.foundation.layout.*
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
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                ChunkWheel(
                    chunks = state.chapter?.chunks ?: emptyList(),
                    currentIndex = state.currentChunkIndex,
                    onCenterChanged = viewModel::onChunkTap,
                    fontSize = prefs.fontSize.sp,
                    lineHeightMultiplier = prefs.lineHeightMultiplier,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
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
