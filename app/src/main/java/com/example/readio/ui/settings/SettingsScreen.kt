package com.example.readio.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.domain.model.GptSoVitsLanguage
import com.example.readio.domain.model.ReadingTheme
import com.example.readio.domain.model.TranslationLanguage
import com.example.readio.domain.model.TranslationProvider
import com.example.readio.domain.model.TtsProvider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ttsConfig        by viewModel.ttsConfig.collectAsStateWithLifecycle()
    val prefs            by viewModel.readingPrefs.collectAsStateWithLifecycle()
    val clearingAudio    by viewModel.clearingAudio.collectAsStateWithLifecycle()
    val gptVoices        by viewModel.gptVoices.collectAsStateWithLifecycle()
    val gptVoicesLoading by viewModel.gptVoicesLoading.collectAsStateWithLifecycle()
    val gptVoicesError   by viewModel.gptVoicesError.collectAsStateWithLifecycle()
    val gptVoiceUploading by viewModel.gptVoiceUploading.collectAsStateWithLifecycle()

    // ── Volcengine credential fields ──────────────────────────────────────────
    var volcAppId    by remember(ttsConfig.volcAppId)     { mutableStateOf(ttsConfig.volcAppId) }
    var volcAccessKey by remember(ttsConfig.volcAccessKey) { mutableStateOf(ttsConfig.volcAccessKey) }
    var volcSpeaker  by remember(ttsConfig.volcSpeaker)   { mutableStateOf(ttsConfig.volcSpeaker) }
    var volcCredsSaved by remember { mutableStateOf(false) }
    LaunchedEffect(volcAppId, volcAccessKey, volcSpeaker) { volcCredsSaved = false }

    // ── GPT-SoVITS fields ────────────────────────────────────────────────────
    var gptUrl      by remember(ttsConfig.gptSoVitsUrl)          { mutableStateOf(ttsConfig.gptSoVitsUrl) }
    var gptToken    by remember(ttsConfig.gptSoVitsApiToken)      { mutableStateOf(ttsConfig.gptSoVitsApiToken) }
    var gptTextLang by remember(ttsConfig.gptSoVitsTextLanguage)  { mutableStateOf(ttsConfig.gptSoVitsTextLanguage) }
    var gptVoice    by remember(ttsConfig.gptSoVitsVoice)         { mutableStateOf(ttsConfig.gptSoVitsVoice) }
    var gptConfigSaved by remember { mutableStateOf(false) }
    LaunchedEffect(gptUrl, gptToken, gptTextLang, gptVoice) { gptConfigSaved = false }

    // ── GPT voice upload fields ───────────────────────────────────────────────
    var uploadName     by remember { mutableStateOf("") }
    var uploadLang     by remember { mutableStateOf("zh") }
    var uploadTranscript by remember { mutableStateOf("") }
    var uploadUri      by remember { mutableStateOf<Uri?>(null) }
    var uploadFilename by remember { mutableStateOf("") }
    var showUploadSection by remember { mutableStateOf(false) }

    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadUri = uri
            uploadFilename = uri.lastPathSegment ?: uri.toString()
        }
    }

    // ── Translation & display ─────────────────────────────────────────────────
    var translationProvider by remember(prefs.translationProvider) { mutableStateOf(prefs.translationProvider) }
    var translationLanguage by remember(prefs.translationLanguage) { mutableStateOf(prefs.translationLanguage) }
    var chunkSize   by remember(prefs.chunkSize)            { mutableIntStateOf(prefs.chunkSize) }
    var fontSize    by remember(prefs.fontSize)             { mutableIntStateOf(prefs.fontSize) }
    var lineHeight  by remember(prefs.lineHeightMultiplier) { mutableFloatStateOf(prefs.lineHeightMultiplier) }
    var readingTheme by remember(prefs.readingTheme)        { mutableStateOf(prefs.readingTheme) }
    var speechRate  by remember(ttsConfig.speechRate)       { mutableFloatStateOf(ttsConfig.speechRate) }

    // ── UI-only dropdown/dialog state ─────────────────────────────────────────
    var globalProviderMenuExpanded      by remember { mutableStateOf(false) }
    var volcVoiceMenuExpanded           by remember { mutableStateOf(false) }
    var androidLocaleMenuExpanded       by remember { mutableStateOf(false) }
    var translationProviderMenuExpanded by remember { mutableStateOf(false) }
    var translationLangMenuExpanded     by remember { mutableStateOf(false) }
    var gptTextLangMenuExpanded         by remember { mutableStateOf(false) }
    var gptVoiceMenuExpanded            by remember { mutableStateOf(false) }
    var uploadLangMenuExpanded          by remember { mutableStateOf(false) }
    var volcKeyVisible by remember { mutableStateOf(false) }
    var gptTokenVisible by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showDeleteVoiceDialog by remember { mutableStateOf(false) }

    // Current volcSpeaker display name
    val volcVoiceOptions = TtsVoiceCatalog.byProvider[TtsProvider.VOLCENGINE] ?: emptyList()
    val volcVoiceLabel   = volcVoiceOptions.firstOrNull { it.id == volcSpeaker }?.label
                          ?: volcSpeaker.ifEmpty { "（未选择）" }

    val androidLocaleOptions = TtsVoiceCatalog.byProvider[TtsProvider.LOCAL_ANDROID] ?: emptyList()
    val androidLocaleLabel   = androidLocaleOptions.firstOrNull { it.id == ttsConfig.androidLocale }?.label
                               ?: ttsConfig.androidLocale

    // GPT-SoVITS voice display — prefer server-fetched list, else show saved voice ID
    val gptVoiceLabel = gptVoices.firstOrNull { it.id == gptVoice }?.displayName
                        ?: gptVoice.ifEmpty { "（未选择）" }

    // ── Navigation / flush helper ─────────────────────────────────────────────
    val flushAndBack: () -> Unit = {
        viewModel.updateVolcCredentials(volcAppId, volcAccessKey, volcSpeaker)
        viewModel.updateGptSoVitsConfig(gptUrl, gptToken, gptTextLang, gptVoice)
        onBack()
    }
    androidx.activity.compose.BackHandler { flushAndBack() }

    // ── Dialogs ───────────────────────────────────────────────────────────────

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

    if (showDeleteVoiceDialog && gptVoice.isNotEmpty()) {
        val voiceName = gptVoices.firstOrNull { it.id == gptVoice }?.displayName ?: gptVoice
        AlertDialog(
            onDismissRequest = { showDeleteVoiceDialog = false },
            title = { Text("删除音色？") },
            text  = { Text("「$voiceName」将从服务器删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteVoiceDialog = false
                    viewModel.deleteGptVoice(gptVoice, gptUrl, gptToken) { gptVoice = "" }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVoiceDialog = false }) { Text("取消") }
            }
        )
    }

    // Error snackbar for voice operations
    if (gptVoicesError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearGptVoicesError() },
            title = { Text("操作失败") },
            text  = { Text(gptVoicesError ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearGptVoicesError() }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = flushAndBack) {
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

            // ════════ 全局默认朗读引擎 ═══════════════════════════════════════
            Text("朗读引擎", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("全局默认引擎", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = globalProviderMenuExpanded,
                    onExpandedChange = { globalProviderMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = ttsConfig.provider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = globalProviderMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = globalProviderMenuExpanded,
                        onDismissRequest = { globalProviderMenuExpanded = false }
                    ) {
                        TtsProvider.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.displayName) },
                                onClick = { globalProviderMenuExpanded = false; viewModel.updateTtsProvider(p) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 火山引擎（云端）════════════════════════════════════════
            Text("火山引擎（云端）", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App ID", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = volcAppId, onValueChange = { volcAppId = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("火山引擎控制台 App ID") }, singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Access Key", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = volcAccessKey, onValueChange = { volcAccessKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Bearer Token") },
                    visualTransformation = if (volcKeyVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { volcKeyVisible = !volcKeyVisible }) {
                            Icon(
                                if (volcKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (volcKeyVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音色", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = volcVoiceMenuExpanded,
                    onExpandedChange = { volcVoiceMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = volcVoiceLabel, onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = volcVoiceMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = volcVoiceMenuExpanded,
                        onDismissRequest = { volcVoiceMenuExpanded = false }
                    ) {
                        volcVoiceOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { volcSpeaker = option.id; volcVoiceMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    viewModel.updateVolcCredentials(volcAppId, volcAccessKey, volcSpeaker)
                    volcCredsSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (volcCredsSaved) "✓ 已保存" else "保存凭据") }

            HorizontalDivider()

            // ════════ GPT-SoVITS（本地推理）══════════════════════════════════
            Text("GPT-SoVITS（本地推理）", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Text(
                "连接本地 GPU 推理服务器（readio-tts）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Server URL
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务器地址", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = gptUrl, onValueChange = { gptUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://192.168.x.x:8000") }, singleLine = true
                )
            }

            // API Token
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API Token（可选）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = gptToken, onValueChange = { gptToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("留空表示无鉴权") },
                    visualTransformation = if (gptTokenVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { gptTokenVisible = !gptTokenVisible }) {
                            Icon(
                                if (gptTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (gptTokenVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    singleLine = true
                )
            }

            // Default text language (fallback when book language is UNKNOWN)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("合成语言（默认）", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = gptTextLangMenuExpanded,
                    onExpandedChange = { gptTextLangMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = GptSoVitsLanguage.labelFor(gptTextLang),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        supportingText = { Text("读取章节语言时自动覆盖此设置") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gptTextLangMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = gptTextLangMenuExpanded,
                        onDismissRequest = { gptTextLangMenuExpanded = false }
                    ) {
                        GptSoVitsLanguage.all.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { gptTextLang = code; gptTextLangMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            // Voice selection — fetched from server
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("音色", style = MaterialTheme.typography.labelLarge)
                    // Fetch button
                    TextButton(
                        onClick = { viewModel.fetchGptVoices(gptUrl, gptToken) },
                        enabled = !gptVoicesLoading && gptUrl.isNotBlank()
                    ) {
                        if (gptVoicesLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("获取音色列表")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExposedDropdownMenuBox(
                        expanded = gptVoiceMenuExpanded,
                        onExpandedChange = { gptVoiceMenuExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = gptVoiceLabel, onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            placeholder = { Text("点击右侧按钮获取列表") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gptVoiceMenuExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = gptVoiceMenuExpanded && gptVoices.isNotEmpty(),
                            onDismissRequest = { gptVoiceMenuExpanded = false }
                        ) {
                            gptVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(voice.displayName)
                                            Text(
                                                GptSoVitsLanguage.labelFor(voice.referenceLanguage),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = { gptVoice = voice.id; gptVoiceMenuExpanded = false }
                                )
                            }
                        }
                    }
                    // Delete voice button
                    if (gptVoice.isNotEmpty()) {
                        IconButton(onClick = { showDeleteVoiceDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除所选音色",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Save connection + voice config
            Button(
                onClick = {
                    viewModel.updateGptSoVitsConfig(gptUrl, gptToken, gptTextLang, gptVoice)
                    gptConfigSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (gptConfigSaved) "✓ 已保存" else "保存配置") }

            // Upload new voice — collapsible section
            OutlinedButton(
                onClick = { showUploadSection = !showUploadSection },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (showUploadSection) "收起上传音色" else "上传新音色") }

            if (showUploadSection) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("上传参考音频", style = MaterialTheme.typography.titleSmall)

                        OutlinedTextField(
                            value = uploadName, onValueChange = { uploadName = it },
                            label = { Text("显示名称") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )

                        // Reference language dropdown
                        ExposedDropdownMenuBox(
                            expanded = uploadLangMenuExpanded,
                            onExpandedChange = { uploadLangMenuExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = GptSoVitsLanguage.labelFor(uploadLang),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("参考音频语言") },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = uploadLangMenuExpanded) }
                            )
                            ExposedDropdownMenu(
                                expanded = uploadLangMenuExpanded,
                                onDismissRequest = { uploadLangMenuExpanded = false }
                            ) {
                                GptSoVitsLanguage.all.forEach { (code, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { uploadLang = code; uploadLangMenuExpanded = false }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = uploadTranscript, onValueChange = { uploadTranscript = it },
                            label = { Text("参考文字（transcript）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2, maxLines = 4
                        )

                        // WAV file picker
                        OutlinedButton(
                            onClick = { wavPicker.launch("audio/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (uploadFilename.isEmpty()) "选择 WAV 文件" else "已选：$uploadFilename")
                        }

                        val canUpload = uploadName.isNotBlank() && uploadTranscript.isNotBlank()
                            && uploadUri != null && gptUrl.isNotBlank()

                        Button(
                            onClick = {
                                val uri = uploadUri ?: return@Button
                                viewModel.uploadGptVoice(
                                    uploadName, uploadLang, uploadTranscript, uri, gptUrl, gptToken
                                )
                                // Reset form on submit
                                uploadName = ""; uploadTranscript = ""; uploadUri = null; uploadFilename = ""
                                showUploadSection = false
                            },
                            enabled = canUpload && !gptVoiceUploading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (gptVoiceUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("上传")
                        }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 系统 TTS ════════════════════════════════════════════════
            Text("系统 TTS", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("语言", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = androidLocaleMenuExpanded,
                    onExpandedChange = { androidLocaleMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = androidLocaleLabel, onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = androidLocaleMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = androidLocaleMenuExpanded,
                        onDismissRequest = { androidLocaleMenuExpanded = false }
                    ) {
                        androidLocaleOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { androidLocaleMenuExpanded = false; viewModel.updateAndroidLocale(option.id) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 朗读速度 ════════════════════════════════════════════════
            Text("朗读速度", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val rateLabel = "%.2f".format(speechRate).trimEnd('0').trimEnd('.')
                Text("语速 — ${rateLabel}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = speechRate,
                    onValueChange = { speechRate = (it * 4).roundToInt() / 4f },
                    onValueChangeFinished = { viewModel.updateSpeechRate(speechRate) },
                    valueRange = 0.5f..2.0f, steps = 5
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0.5×", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2×", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()

            // ════════ 翻译 ════════════════════════════════════════════════════
            Text("翻译", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("翻译引擎", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = translationProviderMenuExpanded,
                    onExpandedChange = { translationProviderMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = translationProvider.displayName, onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationProviderMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = translationProviderMenuExpanded,
                        onDismissRequest = { translationProviderMenuExpanded = false }
                    ) {
                        TranslationProvider.entries.forEach { tp ->
                            DropdownMenuItem(
                                text = { Text(tp.displayName) },
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("翻译目标语言", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = translationLangMenuExpanded,
                    onExpandedChange = { translationLangMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = translationLanguage.label, onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationLangMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = translationLangMenuExpanded,
                        onDismissRequest = { translationLangMenuExpanded = false }
                    ) {
                        TranslationLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.label) },
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

            // ════════ 阅读显示 ════════════════════════════════════════════════
            Text("阅读显示", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每段字数 — ~$chunkSize 字", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = chunkSize.toFloat(),
                    onValueChange = { chunkSize = (it / 50).roundToInt() * 50 },
                    onValueChangeFinished = { viewModel.updateChunkSize(chunkSize) },
                    valueRange = 50f..300f, steps = 4
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50",  style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("300", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字体大小 — ${fontSize}sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { fontSize = it.roundToInt() },
                    onValueChangeFinished = { viewModel.updateFontSize(fontSize) },
                    valueRange = 12f..24f, steps = 11
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("小", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("大", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("行高 — ${"%.1f".format(lineHeight)}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = lineHeight,
                    onValueChange = { lineHeight = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { viewModel.updateLineHeight(lineHeight) },
                    valueRange = 1.0f..2.0f, steps = 9
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("紧凑", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("宽松", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("背景主题", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReadingTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = readingTheme == theme,
                            onClick = { readingTheme = theme; viewModel.updateReadingTheme(theme) },
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
                onClick = { showClearAllDialog = true },
                enabled = !clearingAudio,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
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
