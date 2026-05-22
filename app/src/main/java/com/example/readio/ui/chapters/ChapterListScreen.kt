package com.example.readio.ui.chapters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.ChapterAudioStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    viewModel: ChapterListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.isBulkDownloading) {
                            Text(
                                "正在下载 ${state.bulkDone}/${state.bulkTotal}…",
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
                    if (state.isBulkDownloading) {
                        IconButton(onClick = viewModel::cancelBulkDownload) {
                            Icon(Icons.Default.Clear, contentDescription = "取消下载")
                        }
                    } else {
                        IconButton(
                            onClick = viewModel::downloadAll,
                            enabled = state.chapters.any {
                                it.audioStatus !is ChapterAudioStatus.Downloaded
                            }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "全部下载")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp
                    )
                ) {
                    itemsIndexed(state.chapters, key = { _, item -> item.chapterIndex.id }) { _, item ->
                        ChapterItem(
                            item = item,
                            onClick = { onOpenChapter(item.chapterIndex.id) },
                            onDownload = { viewModel.downloadChapter(item) },
                            onClear = { viewModel.clearChapter(item) }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    item: ChapterUiItem,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onClear: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("删除音频？") },
            text = { Text("「${item.chapterIndex.title}」的音频将被删除，下次播放需重新下载。") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; onClear() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "${item.chapterIndex.indexInBook + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp)
        )

        Text(
            text = item.chapterIndex.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        when (val status = item.audioStatus) {
            is ChapterAudioStatus.NotDownloaded -> {
                IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载音频",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is ChapterAudioStatus.Downloading -> {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }
            is ChapterAudioStatus.Downloaded -> {
                IconButton(onClick = { showClearDialog = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete cached audio",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is ChapterAudioStatus.Error -> {
                IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "重试下载",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
