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
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.ChapterAudioStatus
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    viewModel: ChapterListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    if (state.isDownloadableProvider) {
                        if (state.isBulkDownloading) {
                            IconButton(onClick = viewModel::cancelBulkDownload) {
                                Icon(Icons.Default.Clear, contentDescription = "取消")
                            }
                        } else {
                            IconButton(
                                onClick = viewModel::downloadAll,
                                enabled = state.chapters.any {
                                    it.audioStatus is ChapterAudioStatus.NotDownloaded ||
                                    it.audioStatus is ChapterAudioStatus.Error
                                }
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "全部下载")
                            }
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
                            item              = item,
                            showAudioControls = state.isDownloadableProvider,
                            onClick           = { onOpenChapter(item.chapterIndex.id) },
                            onDownload        = { viewModel.downloadChapter(item) },
                            onImportTaskId    = { taskId -> viewModel.importTaskId(item.chapterIndex.id, taskId) },
                            onClear           = { viewModel.clearChapter(item) }
                        )
                        HorizontalDivider(thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    item: ChapterUiItem,
    showAudioControls: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onImportTaskId: (String) -> Unit,
    onClear: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }
    /** Import-task-ID dialog — only for the edge case where user has an external task ID. */
    var showImportDialog by remember { mutableStateOf(false) }

    // ── Clear confirmation ────────────────────────────────────────────────────
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

    // ── Import task ID dialog ─────────────────────────────────────────────────
    // Only needed when user wants to paste a task ID from an external source.
    if (showImportDialog) {
        var taskIdInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("粘贴任务 ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "如果你已有 Volcengine 合成任务 ID，粘贴后点「导入并下载」。\n" +
                        "否则直接关闭此对话框，点下载按钮新建任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value         = taskIdInput,
                        onValueChange = { taskIdInput = it },
                        label         = { Text("Task ID") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = taskIdInput.isNotBlank(),
                    onClick = {
                        showImportDialog = false
                        onImportTaskId(taskIdInput.trim())
                        onDownload()
                    }
                ) { Text("导入并下载") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
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
            text  = "${item.chapterIndex.indexInBook + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = item.chapterIndex.title,
                style    = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Show progress label when downloading
            val label = (item.audioStatus as? ChapterAudioStatus.Downloading)?.label
            if (!label.isNullOrBlank()) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Show error message inline so it's readable without logcat
            val errorMsg = (item.audioStatus as? ChapterAudioStatus.Error)?.message
            if (!errorMsg.isNullOrBlank()) {
                Text(
                    text  = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showAudioControls) {
            when (val status = item.audioStatus) {
                is ChapterAudioStatus.NotDownloaded -> {
                    IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Download, contentDescription = "下载音频",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                is ChapterAudioStatus.HasTaskId -> {
                    // Task submitted; user taps to query status once and download if ready.
                    IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.HourglassEmpty,
                            contentDescription = "合成中，点击查询结果",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                is ChapterAudioStatus.Downloading -> {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (status.progress < 0f) {
                            // Indeterminate — server is still synthesising or we're mid-query
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        } else {
                            CircularProgressIndicator(
                                progress    = { status.progress },
                                modifier    = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                }

                is ChapterAudioStatus.Downloaded -> {
                    IconButton(onClick = { showClearDialog = true },
                        modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除音频缓存",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                is ChapterAudioStatus.Error -> {
                    IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = "下载失败，点击重试",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }

                is ChapterAudioStatus.NotApplicable -> { /* Realtime TTS: no download icon */ }
            }
        }
    }
}
