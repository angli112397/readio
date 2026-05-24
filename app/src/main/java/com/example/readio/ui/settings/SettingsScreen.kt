package com.example.readio.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TranslationProvider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ttsConfig     by viewModel.ttsConfig.collectAsStateWithLifecycle()
    val prefs         by viewModel.readingPrefs.collectAsStateWithLifecycle()
    val clearingAudio by viewModel.clearingAudio.collectAsStateWithLifecycle()
    val sherpaState   by viewModel.sherpaState.collectAsStateWithLifecycle()

    // File picker launcher — declared at composable scope (not inside conditionals)
    val launcherVits = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importVitsModel(it) } }

    // Volcengine text fields — saved via explicit button; also flushed on focus-lost and on Back
    var volcAppId     by remember(ttsConfig.volcAppId)     { mutableStateOf(ttsConfig.volcAppId) }
    var volcAccessKey by remember(ttsConfig.volcAccessKey) { mutableStateOf(ttsConfig.volcAccessKey) }
    var credentialsSaved by remember { mutableStateOf(false) }

    // Reset "已保存" indicator whenever the user edits either field.
    LaunchedEffect(volcAppId, volcAccessKey) { credentialsSaved = false }

    // Reading / translation local state (dropdowns save immediately on selection)
    var translationProvider by remember(prefs.translationProvider) { mutableStateOf(prefs.translationProvider) }
    var translationLanguage by remember(prefs.translationLanguage) { mutableStateOf(prefs.translationLanguage) }

    // Slider local state (saved on onValueChangeFinished)
    var chunkSize  by remember(prefs.chunkSize)               { mutableIntStateOf(prefs.chunkSize) }
    var fontSize   by remember(prefs.fontSize)                { mutableIntStateOf(prefs.fontSize) }
    var lineHeight by remember(prefs.lineHeightMultiplier)    { mutableFloatStateOf(prefs.lineHeightMultiplier) }
    var readingTheme by remember(prefs.readingTheme)          { mutableStateOf(prefs.readingTheme) }

    // UI-only state
    var translationProviderMenuExpanded by remember { mutableStateOf(false) }
    var translationLangMenuExpanded     by remember { mutableStateOf(false) }
    var keyVisible          by remember { mutableStateOf(false) }
    var showClearAllDialog  by remember { mutableStateOf(false) }

    // Flush credentials when the user navigates back via system gesture or top-bar button
    val navigateBack: () -> Unit = {
        viewModel.updateVolcCredentials(volcAppId, volcAccessKey)
        onBack()
    }
    BackHandler { navigateBack() }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清除所有音频缓存？") },
            text  = { Text("所有离线音频将被删除，下次播放需要重新合成。") },
            confirmButton = {
                TextButton(onClick = { showClearAllDialog = false; viewModel.clearAllDownloadedAudio() }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ════════ 朗读 ════════════════════════════════════════════════════
            Text("朗读", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("离线语音包", style = MaterialTheme.typography.labelLarge)
                Text(
                    "导入 Sherpa-ONNX 兼容的 .tar.bz2 VITS 语音包，用于本地神经网络朗读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModelSlotRow(
                    label    = "本地 VITS 语音包",
                    state    = sherpaState,
                    onImport = { launcherVits.launch(arrayOf("*/*")) },
                    onDelete = { viewModel.deleteVitsModel() }
                )
            }

            HorizontalDivider()

            // ════════ 翻译 ════════════════════════════════════════════════════
            Text("翻译", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // Translation provider
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("翻译引擎", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded      = translationProviderMenuExpanded,
                    onExpandedChange = { translationProviderMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value        = translationProvider.displayName,
                        onValueChange = {},
                        readOnly     = true,
                        modifier     = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationProviderMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded         = translationProviderMenuExpanded,
                        onDismissRequest = { translationProviderMenuExpanded = false }
                    ) {
                        TranslationProvider.entries.forEach { tp ->
                            DropdownMenuItem(
                                text    = { Text(tp.displayName) },
                                onClick = {
                                    translationProvider = tp
                                    translationProviderMenuExpanded = false
                                    viewModel.updateTranslationProvider(tp)
                                }
                            )
                        }
                    }
                }
            }

            // Translation target language
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("翻译目标语言", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded         = translationLangMenuExpanded,
                    onExpandedChange = { translationLangMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value        = translationLanguage.label,
                        onValueChange = {},
                        readOnly     = true,
                        modifier     = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationLangMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded         = translationLangMenuExpanded,
                        onDismissRequest = { translationLangMenuExpanded = false }
                    ) {
                        TranslationLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text    = { Text(lang.label) },
                                onClick = {
                                    translationLanguage = lang
                                    translationLangMenuExpanded = false
                                    viewModel.updateTranslationLanguage(lang)
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 火山引擎凭据 ════════════════════════════════════════════
            // Always shown — per-book TTS can use Volcengine independently of translation.
            Text("火山引擎", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Text(
                "App ID 和 Access Key 在朗读与翻译服务间共用，配置一次即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // App ID
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App ID", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value         = volcAppId,
                    onValueChange = { volcAppId = it },
                    modifier      = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) viewModel.updateVolcCredentials(volcAppId, volcAccessKey) },
                    placeholder   = { Text("火山引擎控制台 App ID") },
                    singleLine    = true
                )
            }

            // Access Key
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Access Key", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value         = volcAccessKey,
                    onValueChange = { volcAccessKey = it },
                    modifier      = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) viewModel.updateVolcCredentials(volcAppId, volcAccessKey) },
                    placeholder   = { Text("Access Token") },
                    visualTransformation = if (keyVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    trailingIcon  = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (keyVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    singleLine    = true
                )
            }

            // Explicit save button — more reliable than relying on focus-lost / BackHandler alone.
            Button(
                onClick = {
                    viewModel.updateVolcCredentials(volcAppId, volcAccessKey)
                    credentialsSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (credentialsSaved) "✓ 已保存" else "保存凭据")
            }

            HorizontalDivider()

            // ════════ 阅读显示 ════════════════════════════════════════════════
            Text("阅读显示", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // Chunk size
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每段字数 — ~$chunkSize 字", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value                = chunkSize.toFloat(),
                    onValueChange        = { chunkSize = (it / 50).roundToInt() * 50 },
                    onValueChangeFinished = { viewModel.updateChunkSize(chunkSize) },
                    valueRange           = 50f..300f,
                    steps                = 4
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50",  style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("300", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Font size
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字体大小 — ${fontSize}sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value                = fontSize.toFloat(),
                    onValueChange        = { fontSize = it.roundToInt() },
                    onValueChangeFinished = { viewModel.updateFontSize(fontSize) },
                    valueRange           = 12f..24f,
                    steps                = 11
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("小", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("大", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Line height
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("行高 — ${"%.1f".format(lineHeight)}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value                = lineHeight,
                    onValueChange        = { lineHeight = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { viewModel.updateLineHeight(lineHeight) },
                    valueRange           = 1.0f..2.0f,
                    steps                = 9
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("紧凑", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("宽松", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Reading theme
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("背景主题", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReadingTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = readingTheme == theme,
                            onClick  = {
                                readingTheme = theme
                                viewModel.updateReadingTheme(theme)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, ReadingTheme.entries.size)
                        ) { Text(theme.label) }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 存储 ════════════════════════════════════════════════════
            Text("存储", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            OutlinedButton(
                onClick  = { showClearAllDialog = true },
                enabled  = !clearingAudio,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)
            ) {
                if (clearingAudio)
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else
                    Text("清除所有音频缓存")
            }
        }
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

/**
 * Displays the import/status UI for a single Sherpa-ONNX model slot.
 *
 * States:
 *  - **Empty / Failed** → shows an import button (+ error message for Failed)
 *  - **Importing** → shows a progress bar and percentage
 *  - **Ready** → shows "✓ 已就绪" with re-import and delete actions
 */
@Composable
private fun ModelSlotRow(
    label   : String,
    state   : ModelImportState,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)

        when (state) {
            is ModelImportState.Empty, is ModelImportState.Failed -> {
                if (state is ModelImportState.Failed) {
                    Text(
                        "导入失败：${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick  = onImport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state is ModelImportState.Failed) "重新导入 .tar.bz2" else "导入 .tar.bz2")
                }
            }

            is ModelImportState.Importing -> {
                val progress = state.progress
                Text(
                    if (progress >= 0) "正在解压… ${(progress * 100).toInt()}%"
                    else               "正在解压…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (progress >= 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            is ModelImportState.Ready -> {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "✓ 已就绪",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onImport) { Text("重新导入") }
                        TextButton(
                            onClick = onDelete,
                            colors  = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("删除") }
                    }
                }
            }
        }
    }
}
