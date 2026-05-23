package com.example.readio.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TtsProvider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ttsConfig by viewModel.ttsConfig.collectAsStateWithLifecycle()
    val prefs by viewModel.readingPrefs.collectAsStateWithLifecycle()
    val clearingAudio by viewModel.clearingAudio.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // TTS state
    var selectedProvider by remember(ttsConfig.provider) { mutableStateOf(ttsConfig.provider) }
    var androidLocale by remember(ttsConfig.androidLocale) { mutableStateOf(ttsConfig.androidLocale) }
    var volcAppId by remember(ttsConfig.volcAppId) { mutableStateOf(ttsConfig.volcAppId) }
    var volcAccessKey by remember(ttsConfig.volcAccessKey) { mutableStateOf(ttsConfig.volcAccessKey) }
    var volcSpeaker by remember(ttsConfig.volcSpeaker) { mutableStateOf(ttsConfig.volcSpeaker) }
    var speechRate by remember(ttsConfig.speechRate) { mutableFloatStateOf(ttsConfig.speechRate) }

    // Reading state
    var chunkSize by remember(prefs.chunkSize) { mutableIntStateOf(prefs.chunkSize) }
    var fontSize by remember(prefs.fontSize) { mutableIntStateOf(prefs.fontSize) }
    var lineHeight by remember(prefs.lineHeightMultiplier) { mutableFloatStateOf(prefs.lineHeightMultiplier) }
    var readingTheme by remember(prefs.readingTheme) { mutableStateOf(prefs.readingTheme) }
    var translationLanguage by remember(prefs.translationLanguage) { mutableStateOf(prefs.translationLanguage) }

    // Menu state
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var translationLangMenuExpanded by remember { mutableStateOf(false) }
    var localeMenuExpanded by remember { mutableStateOf(false) }
    var volcSpeakerMenuExpanded by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val voices = remember(selectedProvider) {
        TtsVoiceCatalog.byProvider[selectedProvider] ?: emptyList()
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清除所有音频缓存？") },
            text = { Text("所有离线音频将被删除，下次播放需要重新下载。") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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

            // ════════ 朗读设置 ════════════════════════════════════════════════
            Text("朗读", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // Provider
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TTS 模式", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) }
                    )
                    ExposedDropdownMenu(expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }) {
                        TtsProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    selectedProvider = provider
                                    if (provider == TtsProvider.VOLCENGINE && volcSpeaker.isBlank())
                                        volcSpeaker = TtsVoiceCatalog.defaultVoice(provider)
                                    providerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── LOCAL_ANDROID 设置 ──
            if (selectedProvider == TtsProvider.LOCAL_ANDROID) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("语言 / Locale", style = MaterialTheme.typography.labelLarge)
                    ExposedDropdownMenuBox(
                        expanded = localeMenuExpanded,
                        onExpandedChange = { localeMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = voices.find { it.id == androidLocale }?.label ?: androidLocale,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = localeMenuExpanded) }
                        )
                        ExposedDropdownMenu(expanded = localeMenuExpanded,
                            onDismissRequest = { localeMenuExpanded = false }) {
                            voices.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label) },
                                    onClick = { androidLocale = v.id; localeMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // ── VOLCENGINE 设置 ──
            if (selectedProvider == TtsProvider.VOLCENGINE) {
                // App ID
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App ID", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = volcAppId,
                        onValueChange = { volcAppId = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("火山引擎控制台 App ID") },
                        singleLine = true
                    )
                }

                // Access Key
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Access Key", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = volcAccessKey,
                        onValueChange = { volcAccessKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Access Token") },
                        visualTransformation = if (keyVisible) VisualTransformation.None
                                              else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "隐藏" else "显示"
                                )
                            }
                        },
                        singleLine = true
                    )
                }

                // Speaker
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音色", style = MaterialTheme.typography.labelLarge)
                    ExposedDropdownMenuBox(
                        expanded = volcSpeakerMenuExpanded,
                        onExpandedChange = { volcSpeakerMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = voices.find { it.id == volcSpeaker }?.label ?: volcSpeaker,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = volcSpeakerMenuExpanded) }
                        )
                        ExposedDropdownMenu(expanded = volcSpeakerMenuExpanded,
                            onDismissRequest = { volcSpeakerMenuExpanded = false }) {
                            voices.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label) },
                                    onClick = { volcSpeaker = v.id; volcSpeakerMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // Speed (common)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("播放速度 — ${"%.2f".format(speechRate)}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = speechRate,
                    onValueChange = { speechRate = (it * 4).roundToInt() / 4f },
                    valueRange = 0.5f..2.0f,
                    steps = 5
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0.5×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2.0×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Translation language
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("翻译目标语言", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = translationLangMenuExpanded,
                    onExpandedChange = { translationLangMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = translationLanguage.label,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationLangMenuExpanded) }
                    )
                    ExposedDropdownMenu(expanded = translationLangMenuExpanded,
                        onDismissRequest = { translationLangMenuExpanded = false }) {
                        TranslationLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.label) },
                                onClick = { translationLanguage = lang; translationLangMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 阅读显示 ════════════════════════════════════════════════
            Text("阅读显示", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // Chunk size
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每段字数 — ~$chunkSize 字", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = chunkSize.toFloat(),
                    onValueChange = { chunkSize = (it / 50).roundToInt() * 50 },
                    valueRange = 50f..300f,
                    steps = 4
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("300", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Font size
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字体大小 — ${fontSize}sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { fontSize = it.roundToInt() },
                    valueRange = 12f..24f,
                    steps = 11
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("小", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("大", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Line height
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("行高 — ${"%.1f".format(lineHeight)}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = lineHeight,
                    onValueChange = { lineHeight = (it * 10).roundToInt() / 10f },
                    valueRange = 1.0f..2.0f,
                    steps = 9
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("紧凑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("宽松", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Reading theme
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("背景主题", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReadingTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = readingTheme == theme,
                            onClick = { readingTheme = theme },
                            shape = SegmentedButtonDefaults.itemShape(index, ReadingTheme.entries.size)
                        ) { Text(theme.label) }
                    }
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        try {
                            viewModel.save(
                                selectedProvider, androidLocale,
                                volcAppId, volcAccessKey, volcSpeaker,
                                speechRate, chunkSize, fontSize, lineHeight,
                                readingTheme, translationLanguage
                            )
                            onBack()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("保存失败：${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }

            HorizontalDivider()

            // ════════ 存储（仅火山引擎模式下有意义）════════════════════════
            if (selectedProvider == TtsProvider.VOLCENGINE) {
                Text("存储", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)

                OutlinedButton(
                    onClick = { showClearAllDialog = true },
                    enabled = !clearingAudio,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    if (clearingAudio) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("清除所有音频缓存")
                }
            }
        }
    }
}
