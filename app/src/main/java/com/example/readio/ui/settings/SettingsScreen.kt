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

    // TTS state
    var selectedProvider by remember(ttsConfig.provider) { mutableStateOf(ttsConfig.provider) }
    var apiKey by remember(ttsConfig.apiKey) { mutableStateOf(ttsConfig.apiKey) }
    var region by remember(ttsConfig.region) { mutableStateOf(ttsConfig.region) }
    var selectedVoice by remember(ttsConfig.voice) { mutableStateOf(ttsConfig.voice) }
    var speechRate by remember(ttsConfig.speechRate) { mutableFloatStateOf(ttsConfig.speechRate) }

    // Reading state
    var chunkSize by remember(prefs.chunkSize) { mutableIntStateOf(prefs.chunkSize) }
    var fontSize by remember(prefs.fontSize) { mutableIntStateOf(prefs.fontSize) }
    var lineHeight by remember(prefs.lineHeightMultiplier) { mutableFloatStateOf(prefs.lineHeightMultiplier) }
    var readingTheme by remember(prefs.readingTheme) { mutableStateOf(prefs.readingTheme) }

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val voices = TtsVoiceCatalog.byProvider[selectedProvider] ?: emptyList()
    val voiceLabel = voices.find { it.id == selectedVoice }?.label ?: selectedVoice
    val regionLabel = azureRegions.find { it.id == region }?.label ?: region

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
                Text("TTS 服务", style = MaterialTheme.typography.labelLarge)
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
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        TtsProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    selectedProvider = provider
                                    selectedVoice = TtsVoiceCatalog.defaultVoice(provider)
                                    providerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // API Key
            if (selectedProvider != TtsProvider.LOCAL_ANDROID) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("API Key", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴 ${selectedProvider.displayName} 密钥") },
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
            }

            // Region (Azure only)
            if (selectedProvider == TtsProvider.AZURE) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("区域", style = MaterialTheme.typography.labelLarge)
                    ExposedDropdownMenuBox(
                        expanded = regionMenuExpanded,
                        onExpandedChange = { regionMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = regionLabel,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionMenuExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = regionMenuExpanded,
                            onDismissRequest = { regionMenuExpanded = false }
                        ) {
                            azureRegions.forEach { r ->
                                DropdownMenuItem(
                                    text = { Text(r.label) },
                                    onClick = { region = r.id; regionMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // Voice
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("语音", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = voiceMenuExpanded,
                    onExpandedChange = { voiceMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = voiceLabel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = voiceMenuExpanded,
                        onDismissRequest = { voiceMenuExpanded = false }
                    ) {
                        voices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice.label) },
                                onClick = { selectedVoice = voice.id; voiceMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            // Speed
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
                        viewModel.save(
                            selectedProvider, apiKey, region, selectedVoice, speechRate,
                            chunkSize, fontSize, lineHeight, readingTheme
                        )
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }

            HorizontalDivider()

            // ════════ 存储 ════════════════════════════════════════════════════
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
