package com.bitperfect.android.ui.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitperfect.android.player.AudioEffectsController

/**
 * Equalizer, bass and treble controls for the Android output path.
 *
 * The header states plainly that these controls do not apply to bit-perfect USB
 * output, because that is the whole point of the dual-path design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel,
    onBackClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetToFlat() }) {
                        Text("Flat")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            PathNotice()

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable equalizer",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (uiState.bands.isEmpty()) {
                            "Unavailable until playback starts"
                        } else {
                            "${uiState.bands.size} bands on this device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.isEnabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                    enabled = uiState.isAttached
                )
            }

            uiState.statusMessage?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (uiState.presets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = "Presets", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.presets.forEachIndexed { index, preset ->
                        AssistChip(
                            onClick = { viewModel.applyPreset(index) },
                            label = { Text(preset) },
                            enabled = uiState.isAttached
                        )
                    }
                }
            }

            if (uiState.bands.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = "Bands", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "%.0f dB to +%.0f dB".format(
                        uiState.minLevelDb,
                        uiState.maxLevelDb
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                uiState.bands.forEach { band ->
                    BandSlider(
                        band = band,
                        minLevelMillibel = uiState.minLevelMillibel,
                        maxLevelMillibel = uiState.maxLevelMillibel,
                        enabled = uiState.isAttached && uiState.isEnabled,
                        onLevelChange = { viewModel.setBandLevel(band.index, it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "Tone", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            StrengthSlider(
                label = "Bass boost",
                description = if (uiState.supportsBassBoost) {
                    null
                } else {
                    "Not supported on this device"
                },
                strength = uiState.bassBoostStrength,
                enabled = uiState.isAttached && uiState.isEnabled && uiState.supportsBassBoost,
                onStrengthChange = { viewModel.setBassBoost(it) }
            )

            StrengthSlider(
                label = "Treble",
                description = "Applied as gain on the highest equalizer bands, " +
                    "since Android has no dedicated treble effect",
                strength = uiState.trebleStrength,
                enabled = uiState.isAttached && uiState.isEnabled && uiState.bands.isNotEmpty(),
                onStrengthChange = { viewModel.setTreble(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * States which output path these controls affect.
 */
@Composable
private fun PathNotice() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Applies to Android audio output only",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "These controls modify the audio, so they are never applied to " +
                    "bit-perfect USB output. When playing to a USB DAC in bit-perfect " +
                    "mode, the signal stays untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BandSlider(
    band: EqualizerViewModel.BandState,
    minLevelMillibel: Int,
    maxLevelMillibel: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = band.label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "%+.1f dB".format(band.levelDb),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = band.levelMillibel.toFloat(),
            onValueChange = { onLevelChange(it.toInt()) },
            valueRange = minLevelMillibel.toFloat()..maxLevelMillibel.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StrengthSlider(
    label: String,
    description: String?,
    strength: Int,
    enabled: Boolean,
    onStrengthChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${strength * 100 / AudioEffectsController.MAX_STRENGTH}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Slider(
            value = strength.toFloat(),
            onValueChange = { onStrengthChange(it.toInt()) },
            valueRange = 0f..AudioEffectsController.MAX_STRENGTH.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


