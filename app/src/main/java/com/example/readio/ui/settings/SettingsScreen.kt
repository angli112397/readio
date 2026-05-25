package com.example.readio.ui.settings

import androidx.activity.compose.BackHandler
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
import com.example.readio.domain.model.TranslationProvider
import com.example.readio.domain.model.TtsProvider
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

    // ── Volcengine credential fields ──────────────────────────────────────────
    // remember(key) reinitialises local state each time the DataStore value changes.
    // NO onFocusChanged — it fires isFocused=false on initial composition, saving empty strings.
    var volcAppId     by remember(ttsConfig.volcAppId)     { mutableStateOf(ttsConfig.volcAppId) }
    var volcAccessKey by remember(ttsConfig.volcAccessKey) { mutableStateOf(ttsConfig.volcAccessKey) }
    var volcSpeaker   by remember(ttsConfig.volcSpeaker)   { mutableStateOf(ttsConfig.volcSpeaker) }
    var volcCredsSaved by remember { mutableStateOf(false) }
    LaunchedEffect(volcAppId, volcAccessKey, volcSpeaker) { volcCredsSaved = false }

    // ── Fish Speech URL ───────────────────────────────────────────────────────
    var fishSpeechUrl  by remember(ttsConfig.fishSpeechUrl) { mutableStateOf(ttsConfig.fishSpeechUrl) }
    var fishUrlSaved   by remember { mutableStateOf(false) }
    LaunchedEffect(fishSpeechUrl) { fishUrlSaved = false }

    // ── Translation & display local state ────────────────────────────────────
    var translationProvider by remember(prefs.translationProvider) { mutableStateOf(prefs.translationProvider) }
    var translationLanguage by remember(prefs.translationLanguage) { mutableStateOf(prefs.translationLanguage) }
    var chunkSize   by remember(prefs.chunkSize)            { mutableIntStateOf(prefs.chunkSize) }
    var fontSize    by remember(prefs.fontSize)             { mutableIntStateOf(prefs.fontSize) }
    var lineHeight  by remember(prefs.lineHeightMultiplier) { mutableFloatStateOf(prefs.lineHeightMultiplier) }
    var readingTheme by remember(prefs.readingTheme)        { mutableStateOf(prefs.readingTheme) }
    var speechRate  by remember(ttsConfig.speechRate)       { mutableFloatStateOf(ttsConfig.speechRate) }

    // ── UI-only state ─────────────────────────────────────────────────────────
    var globalProviderMenuExpanded       by remember { mutableStateOf(false) }
    var volcVoiceMenuExpanded            by remember { mutableStateOf(false) }
    var androidLocaleMenuExpanded        by remember { mutableStateOf(false) }
    var translationProviderMenuExpanded  by remember { mutableStateOf(false) }
    var translationLangMenuExpanded      by remember { mutableStateOf(false) }
    var keyVisible         by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    // Current volcSpeaker display name
    val volcVoiceOptions = TtsVoiceCatalog.byProvider[TtsProvider.VOLCENGINE] ?: emptyList()
    val volcVoiceLabel   = volcVoiceOptions.firstOrNull { it.id == volcSpeaker }?.label
                          ?: volcSpeaker.ifEmpty { "（未选择）" }

    // Current androidLocale display name
    val androidLocaleOptions = TtsVoiceCatalog.byProvider[TtsProvider.LOCAL_ANDROID] ?: emptyList()
    val androidLocaleLabel   = androidLocaleOptions.firstOrNull { it.id == ttsConfig.androidLocale }?.label
                               ?: ttsConfig.androidLocale

    // ── Navigation / flush helper ─────────────────────────────────────────────
    val flushAndBack: () -> Unit = {
        viewModel.updateVolcCredentials(volcAppId, volcAccessKey, volcSpeaker)
        viewModel.updateFishSpeechUrl(fishSpeechUrl)
        onBack()
    }
    BackHandler { flushAndBack() }

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
                    expanded         = globalProviderMenuExpanded,
                    onExpandedChange = { globalProviderMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value         = ttsConfig.provider.displayName,
                        onValueChange = {},
                        readOnly      = true,
                        modifier      = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = globalProviderMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded         = globalProviderMenuExpanded,
                        onDismissRequest = { globalProviderMenuExpanded = false }
                    ) {
                        TtsProvider.entries.forEach { p ->
                            DropdownMenuItem(
                                text    = { Text(p.displayName) },
                                onClick = {
                                    globalProviderMenuExpanded = false
                                    viewModel.updateTtsProvider(p)
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ════════ 火山引擎（云端）════════════════════════════════════════
            Text("火山引擎（云端）", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            // App ID
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App ID", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value         = volcAppId,
                    onValueChange = { volcAppId = it },
                    modifier      = Modifier.fillMaxWidth(),
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
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder          = { Text("Bearer Token") },
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

            // Voice (speaker) dropdown
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音色", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded         = volcVoiceMenuExpanded,
                    onExpandedChange = { volcVoiceMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value         = volcVoiceLabel,
                        onValueChange = {},
                        readOnly      = true,
                        modifier      = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = volcVoiceMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded         = volcVoiceMenuExpanded,
                        onDismissRequest = { volcVoiceMenuExpanded = false }
                    ) {
                        volcVoiceOptions.forEach { option ->
                            DropdownMenuItem(
                                text    = { Text(option.label) },
                                onClick = {
                                    volcSpeaker        = option.id
                                    volcVoiceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Explicit save button
            Button(
                onClick = {
                    viewModel.updateVolcCredentials(volcAppId, volcAccessKey, volcSpeaker)
                    volcCredsSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (volcCredsSaved) "✓ 已保存" else "保存凭据")
            }

            HorizontalDivider()

            // ════════ Fish Speech（本地推理）══════════════════════════════════
            Text("Fish Speech（本地推理）", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Text(
                "连接运行 Volcengine 兼容 API 的本地 GPU 推理服务（如 Fish Speech）。无需 App ID / Access Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务器地址", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value         = fishSpeechUrl,
                    onValueChange = { fishSpeechUrl = it },
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text("http://192.168.x.x:8000") },
                    singleLine    = true
                )
            }

            Button(
                onClick = {
                    viewModel.updateFishSpeechUrl(fishSpeechUrl)
                    fishUrlSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (fishUrlSaved) "✓ 已保存" else "保存地址")
            }

            HorizontalDivider()

            // ════════ 系统 TTS ════════════════════════════════════════════════
            Text("系统 TTS", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("语言", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded         = androidLocaleMenuExpanded,
                    onExpandedChange = { androidLocaleMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value         = androidLocaleLabel,
                        onValueChange = {},
                        readOnly      = true,
                        modifier      = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = androidLocaleMenuExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded         = androidLocaleMenuExpanded,
                        onDismissRequest = { androidLocaleMenuExpanded = false }
                    ) {
                        androidLocaleOptions.forEach { option ->
                            DropdownMenuItem(
                                text    = { Text(option.label) },
                                onClick = {
                                    androidLocaleMenuExpanded = false
                                    viewModel.updateAndroidLocale(option.id)
                                }
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
                    value                 = speechRate,
                    onValueChange         = { speechRate = (it * 4).roundToInt() / 4f },
                    onValueChangeFinished = { viewModel.updateSpeechRate(speechRate) },
                    valueRange            = 0.5f..2.0f,
                    steps                 = 5
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

            // Translation provider
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("翻译引擎", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded         = translationProviderMenuExpanded,
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
