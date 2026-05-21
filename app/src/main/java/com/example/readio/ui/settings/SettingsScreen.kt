package com.example.readio.ui.settings

import androidx.compose.foundation.layout.*
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
import com.example.readio.domain.model.TtsProvider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val clearingAudio by viewModel.clearingAudio.collectAsStateWithLifecycle()

    var selectedProvider by remember { mutableStateOf(config.provider) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var region by remember { mutableStateOf(config.region) }
    var selectedVoice by remember { mutableStateOf(config.voice) }
    var speechRate by remember { mutableFloatStateOf(config.speechRate) }

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    // When provider changes, reset voice to that provider's default
    val voices = TtsVoiceCatalog.byProvider[selectedProvider] ?: emptyList()
    val voiceLabel = voices.find { it.id == selectedVoice }?.label ?: selectedVoice
    val speedLabel = remember(speechRate) { "%.2f×".format(speechRate) }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all downloaded audio?") },
            text = { Text("All offline audio will be deleted. You'll need to download chapters again to listen offline.") },
            confirmButton = {
                TextButton(onClick = { showClearAllDialog = false; viewModel.clearAllDownloadedAudio() }) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Provider ────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TTS Provider", style = MaterialTheme.typography.labelLarge)
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

            // ── API Key (cloud providers only) ───────────────────────────────
            if (selectedProvider != TtsProvider.LOCAL_ANDROID) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("API Key", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste your ${selectedProvider.displayName} key") },
                        visualTransformation = if (keyVisible) VisualTransformation.None
                                              else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "Hide key" else "Show key"
                                )
                            }
                        },
                        singleLine = true
                    )
                }
            }

            // ── Region (Azure only) ──────────────────────────────────────────
            if (selectedProvider == TtsProvider.AZURE) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Region", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. eastasia, eastus") },
                        singleLine = true
                    )
                }
            }

            // ── Voice ────────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice", style = MaterialTheme.typography.labelLarge)
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

            // ── Speed ────────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback Speed — $speedLabel", style = MaterialTheme.typography.labelLarge)
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

            Button(
                onClick = {
                    viewModel.save(selectedProvider, apiKey, region, selectedVoice, speechRate)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Storage", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { showClearAllDialog = true },
                    enabled = !clearingAudio,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (clearingAudio) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Clear all downloaded audio")
                    }
                }
            }
        }
    }
}
