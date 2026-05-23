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

    // Forward one-shot messages (e.g., "task still pending") to the snackbar.
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
                                "正在提交 ${state.bulkDone}/${state.bulkTotal}…",
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
                                onClick = viewModel::submitAll,
                                enabled = state.chapters.any {
                                    it.audioStatus is ChapterAudioStatus.NotDownloaded ||
                                    it.audioStatus is ChapterAudioStatus.Error
                                }
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "全部提交")
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
                            item = item,
                            showAudioControls = state.isDownloadableProvider,
                            onClick = { onOpenChapter(item.chapterIndex.id) },
                            onSubmitTask = { viewModel.submitTask(item) },
                            onFetchResult = { viewModel.fetchResult(item) },
                            onImportTaskId = { taskId -> viewModel.importTaskId(item.chapterIndex.id, taskId) },
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
    showAudioControls: Boolean,
    onClick: () -> Unit,
    onSubmitTask: () -> Unit,
    onFetchResult: () -> Unit,
    onImportTaskId: (String) -> Unit,
    onClear: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showTaskDialog by remember { mutableStateOf(false) }
    // true = opened from download icon (NotDownloaded); false = opened from hourglass (HasTaskId)
    var taskDialogIsNew by remember { mutableStateOf(false) }

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

    // ── Task ID dialog ────────────────────────────────────────────────────────
    // Opens from the download icon (taskDialogIsNew=true) or hourglass (false).
    if (showTaskDialog) {
        val currentTaskId = (item.audioStatus as? ChapterAudioStatus.HasTaskId)?.taskId ?: ""
        var taskIdInput by remember(currentTaskId) { mutableStateOf(currentTaskId) }

        AlertDialog(
            onDismissRequest = { showTaskDialog = false },
            title = { Text("任务 ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (taskDialogIsNew)
                            "粘贴已有 Task ID 直接获取结果，或留空新建合成任务。"
                        else
                            "任务已提交，合成完成后点「获取结果」下载音频。\n" +
                            "也可粘贴其他 Task ID 覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = taskIdInput,
                        onValueChange = { taskIdInput = it },
                        label = { Text("Task ID（留空则新建任务）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                // "获取结果" — import the given ID (or existing) and fetch
                TextButton(
                    enabled = taskIdInput.isNotBlank(),
                    onClick = {
                        showTaskDialog = false
                        if (taskIdInput.trim() != currentTaskId) {
                            onImportTaskId(taskIdInput.trim())
                        }
                        onFetchResult()
                    }
                ) { Text("获取结果") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // "新建任务" — only show when opened from download icon
                    if (taskDialogIsNew) {
                        TextButton(onClick = { showTaskDialog = false; onSubmitTask() }) {
                            Text("新建任务")
                        }
                    }
                    TextButton(onClick = { showTaskDialog = false }) { Text("取消") }
                }
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

        if (showAudioControls) {
            when (val status = item.audioStatus) {
                is ChapterAudioStatus.NotDownloaded -> {
                    IconButton(
                        onClick = { taskDialogIsNew = true; showTaskDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "提交合成任务",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ChapterAudioStatus.HasTaskId -> {
                    IconButton(
                        onClick = { taskDialogIsNew = false; showTaskDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.HourglassEmpty,
                            contentDescription = "任务已提交，点击获取结果",
                            tint = MaterialTheme.colorScheme.primary
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
                        Icon(Icons.Default.Delete, contentDescription = "删除音频缓存",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ChapterAudioStatus.Error -> {
                    IconButton(
                        onClick = { taskDialogIsNew = true; showTaskDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "查看错误 / 重试",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                is ChapterAudioStatus.NotApplicable -> { /* LOCAL TTS, no icon */ }
            }
        }
    }
}
