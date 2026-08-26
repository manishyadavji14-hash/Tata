package com.bitperfect.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.theme.ErrorRed

/**
 * SettingsScreen - Complete settings interface for BitPerfect.
 *
 * Sections:
 * - Audio Output: BitPerfect mode toggle (with warning), USB output selection,
 *   buffer size slider (ms), latency display
 * - Format: Auto sample-rate switching, DSD output mode dropdown
 *   (Native DSD / DoP / PCM), Native DSD preference, DoP preference, PCM fallback policy
 * - Processing: ReplayGain mode (Off/Track/Album), preamp slider, clipping prevention,
 *   crossfade slider
 * - Interface: Theme (System/Light/Dark), debug logging toggle
 * - About: Version, build info, licenses link
 *
 * Clearly warns when a setting disables BitPerfect operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onDiagnosticsClick: () -> Unit = {},
    onLicensesClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val showWarning by viewModel.showWarningDialog.collectAsState()
    val warningMessage by viewModel.warningMessage.collectAsState()

    // BitPerfect warning dialog
    if (showWarning) {
        BitPerfectWarningDialog(
            message = warningMessage,
            onDismiss = { viewModel.dismissWarning() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Audio Output section
            item {
                SectionHeader("Audio Output")
            }
            item {
                SettingsCard {
                    ToggleSetting(
                        title = "BitPerfect Mode",
                        description = "Send audio data unmodified to DAC. No volume adjustment, no resampling.",
                        checked = uiState.bitPerfectMode,
                        onCheckedChange = { viewModel.setBitPerfectMode(it) },
                        isImportant = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SliderSetting(
                        title = "Buffer Size",
                        value = uiState.bufferSizeMs.toFloat(),
                        valueRange = 10f..500f,
                        steps = 48,
                        valueLabel = "${uiState.bufferSizeMs} ms",
                        onValueChange = { viewModel.setBufferSize(it.toInt()) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(
                        label = "Estimated Latency",
                        value = "${uiState.latencyMs} ms"
                    )
                }
            }

            // Format section
            item {
                SectionHeader("Format")
            }
            item {
                SettingsCard {
                    ToggleSetting(
                        title = "Auto Sample Rate Switching",
                        description = "Automatically match DAC sample rate to source material",
                        checked = uiState.autoSampleRate,
                        onCheckedChange = { viewModel.setAutoSampleRate(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DropdownSetting(
                        title = "DSD Output Mode",
                        selectedValue = uiState.dsdOutputMode,
                        options = listOf(
                            "native_dsd" to "Native DSD",
                            "dop" to "DoP (DSD over PCM)",
                            "pcm" to "PCM Conversion"
                        ),
                        onValueChange = { viewModel.setDsdOutputMode(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ToggleSetting(
                        title = "Native DSD Preference",
                        description = "Prefer Native DSD transport when DAC supports it",
                        checked = uiState.nativeDsdPreference,
                        onCheckedChange = { viewModel.setNativeDsdPreference(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleSetting(
                        title = "DoP Preference",
                        description = "Use DoP encoding when Native DSD is not available",
                        checked = uiState.dopPreference,
                        onCheckedChange = { viewModel.setDopPreference(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DropdownSetting(
                        title = "PCM Fallback Policy",
                        selectedValue = uiState.pcmFallbackPolicy,
                        options = listOf(
                            "convert" to "Convert to PCM",
                            "skip" to "Skip Track",
                            "ask" to "Ask User"
                        ),
                        onValueChange = { viewModel.setPcmFallbackPolicy(it) }
                    )
                }
            }

            // Processing section
            item {
                SectionHeader("Processing")
            }
            item {
                SettingsCard {
                    DropdownSetting(
                        title = "ReplayGain",
                        selectedValue = uiState.replayGainMode,
                        options = listOf(
                            "off" to "Off",
                            "track" to "Track Gain",
                            "album" to "Album Gain"
                        ),
                        onValueChange = { viewModel.setReplayGainMode(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SliderSetting(
                        title = "Pre-amp",
                        value = uiState.preampDb,
                        valueRange = -12f..12f,
                        steps = 23,
                        valueLabel = "%.1f dB".format(uiState.preampDb),
                        onValueChange = { viewModel.setPreampDb(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ToggleSetting(
                        title = "Clipping Prevention",
                        description = "Reduce gain to prevent digital clipping with ReplayGain",
                        checked = uiState.clippingPrevention,
                        onCheckedChange = { viewModel.setClippingPrevention(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SliderSetting(
                        title = "Crossfade",
                        value = uiState.crossfadeMs.toFloat(),
                        valueRange = 0f..12000f,
                        steps = 11,
                        valueLabel = if (uiState.crossfadeMs == 0) "Off" else "${uiState.crossfadeMs} ms",
                        onValueChange = { viewModel.setCrossfade(it.toInt()) }
                    )
                }
            }

            // Interface section
            item {
                SectionHeader("Interface")
            }
            item {
                SettingsCard {
                    DropdownSetting(
                        title = "Theme",
                        selectedValue = uiState.themeMode,
                        options = listOf(
                            "system" to "System Default",
                            "light" to "Light",
                            "dark" to "Dark"
                        ),
                        onValueChange = { viewModel.setThemeMode(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ToggleSetting(
                        title = "Debug Logging",
                        description = "Enable verbose logging for troubleshooting",
                        checked = uiState.debugLogging,
                        onCheckedChange = { viewModel.setDebugLogging(it) }
                    )
                }
            }

            // Equalizer section
            item {
                SectionHeader("Equalizer")
            }
            item {
                SettingsCard {
                    Text(
                        text = "(Coming soon - audio engine support pending)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleSetting(
                        title = "Enable Equalizer",
                        description = "Apply frequency adjustments to audio output",
                        checked = uiState.eqEnabled,
                        onCheckedChange = { viewModel.setEqEnabled(it) }
                    )
                    if (uiState.eqEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        DropdownSetting(
                            title = "EQ Preset",
                            selectedValue = uiState.eqPreset,
                            options = listOf(
                                "flat" to "Flat",
                                "rock" to "Rock",
                                "pop" to "Pop",
                                "jazz" to "Jazz",
                                "classical" to "Classical",
                                "bass_boost" to "Bass Boost",
                                "treble_boost" to "Treble Boost",
                                "custom" to "Custom"
                            ),
                            onValueChange = { viewModel.setEqPreset(it) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Note: Enabling equalizer disables bit-perfect mode. " +
                                "Audio will be processed before reaching the DAC.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Library section
            item {
                SectionHeader("Library")
            }
            item {
                ScanDirectoriesCard(
                    scanDirectories = uiState.scanDirectories,
                    onAddDirectory = { viewModel.addScanDirectory(it) },
                    onRemoveDirectory = { viewModel.removeScanDirectory(it) }
                )
            }

            // About section
            item {
                SectionHeader("About")
            }
            item {
                SettingsCard {
                    InfoRow(label = "Version", value = uiState.appVersion)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "Build", value = uiState.buildInfo)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDiagnosticsClick) {
                        Text("DAC Diagnostics")
                    }
                    TextButton(onClick = onLicensesClick) {
                        Text("Open Source Licenses")
                    }
                }
            }

            // Bottom padding
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ScanDirectoriesCard(
    scanDirectories: Set<String>,
    onAddDirectory: (String) -> Unit,
    onRemoveDirectory: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newDirPath by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Scan Directory") },
            text = {
                Column {
                    Text(
                        "Enter the full path to a directory to scan for music files.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDirPath,
                        onValueChange = { newDirPath = it },
                        label = { Text("Directory Path") },
                        placeholder = { Text("/storage/emulated/0/Music") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newDirPath.isNotBlank()) {
                            onAddDirectory(newDirPath.trim())
                            newDirPath = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newDirPath = ""
                    showAddDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Scan Directories",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Directories to scan for audio files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showAddDialog = true }) {
                Text("Add")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (scanDirectories.isEmpty()) {
            Text(
                text = "No directories configured. Default paths will be used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (dir in scanDirectories.sorted()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dir,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { onRemoveDirectory(dir) }) {
                        Text(
                            "Remove",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BitPerfectWarningDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = ErrorRed
            )
        },
        title = {
            Text("BitPerfect Warning")
        },
        text = {
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("I Understand")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    description: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isImportant: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isImportant) FontWeight.Bold else FontWeight.Normal
                )
                if (isImportant) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    title: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selectedValue }?.second ?: selectedValue

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
