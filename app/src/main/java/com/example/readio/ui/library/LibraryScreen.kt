package com.example.readio.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.EpubBook
import com.example.readio.domain.model.TtsProvider
import com.example.readio.ui.settings.TtsChoice
import com.example.readio.ui.settings.TtsVoiceCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookOpen: (String) -> Unit,
    onSettingsOpen: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val books     by viewModel.books.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val error     by viewModel.error.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(GetContent()) { uri ->
        uri?.let { viewModel.import(it) }
    }

    error?.let {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("导入失败") },
            text  = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("确认") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readio") },
                actions = {
                    IconButton(onClick = onSettingsOpen) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (!importing) launcher.launch("application/epub+zip") }) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = "导入书籍")
                }
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            EmptyLibrary(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start  = 16.dp, end    = 16.dp,
                    top    = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book        = book,
                        onClick     = { onBookOpen(book.id) },
                        onDelete    = { viewModel.delete(book) },
                        onTtsChange = { provider, voice -> viewModel.setBookTts(book.id, provider, voice) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: EpubBook,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTtsChange: (TtsProvider?, String?) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTtsDialog    by remember { mutableStateOf(false) }
    val currentChoice = TtsVoiceCatalog.findChoice(book.ttsProvider, book.ttsVoice)

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这本书？") },
            text  = { Text("「${book.title}」将从书库中移除。") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showTtsDialog) {
        TtsPickerDialog(
            currentChoice = currentChoice,
            onSelect = { choice ->
                onTtsChange(choice?.provider, choice?.voiceId)
                showTtsDialog = false
            },
            onDismiss = { showTtsDialog = false }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier              = Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = book.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = book.title,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis
                )
                book.author?.let {
                    Text(
                        text     = it,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text  = "${book.chapterCount} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AssistChip(
                    onClick  = { showTtsDialog = true },
                    label    = {
                        Text(
                            currentChoice?.label ?: "默认 TTS",
                            style    = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.padding(top = 2.dp).height(24.dp)
                )
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TtsPickerDialog(
    currentChoice: TtsChoice?,
    onSelect: (TtsChoice?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("朗读方式") },
        text  = {
            // Column + verticalScroll: LazyColumn inside AlertDialog gets measured at
            // 0 height (AlertDialog's text slot is itself scrollable), making items invisible.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TextButton(
                    onClick  = { onSelect(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "默认（跟随全局设置）",
                        color = if (currentChoice == null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                    )
                }
                HorizontalDivider()
                TtsVoiceCatalog.allChoices.forEach { choice ->
                    TextButton(
                        onClick  = { onSelect(choice) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            choice.label,
                            color = if (choice == currentChoice) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("还没有书", style = MaterialTheme.typography.titleMedium)
            Text(
                "点击 + 导入 EPUB 文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
