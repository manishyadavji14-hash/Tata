package com.bitperfect.android.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the audio pipeline is doing, from file to output.
 *
 * Reads top to bottom in signal order — source, decoding, processing, output —
 * because the question it answers is "what is happening to my audio", and the
 * honest answer is a chain.
 *
 * Anything not actually measured says so rather than showing a zero. A panel like
 * this exists to be trusted, and a confident-looking "0 underruns" that really
 * means "not reported on this path" would make it worse than nothing.
 */
@Composable
fun AudioInfoDialog(
    info: PlayerViewModel.AudioPipelineInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio info") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (info.trackTitle.isNotBlank()) {
                    Text(
                        text = info.trackTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Section("Source")
                InfoRow("Container", info.container)
                InfoRow("Format", info.sourceFormat)

                Section("Decoding")
                InfoRow("Decoder", info.decoder)

                Section("Processing")
                InfoRow("Effects", info.effectsSummary)

                Section("Output")
                InfoRow("Device", info.outputName)
                InfoRow("Path", info.outputMode)
                InfoRow(
                    label = "Bit-perfect",
                    value = if (info.isBitPerfect) {
                        "Yes — samples unmodified"
                    } else {
                        "No — mixed by Android"
                    }
                )
                info.engineSampleRate?.let { InfoRow("Engine rate", "$it Hz") }

                // USB-only facts. Shown only when a DAC is actually streaming, so
                // the panel does not imply a transport that is not in use.
                if (info.isUsbOutputActive || info.transportName != null) {
                    Section("USB transport")
                    InfoRow("Transport", info.transportName ?: "Not reported")
                    InfoRow(
                        label = "Streaming",
                        value = if (info.isUsbOutputActive) "Yes" else "No"
                    )
                }

                Section("Buffer")
                InfoRow(
                    label = "Fill level",
                    value = info.bufferLevelPercent?.let { "$it%" } ?: "Not reported"
                )
                InfoRow(
                    label = "Underruns",
                    value = info.underrunCount?.toString() ?: "Not reported"
                )

                Section("Lock screen")
                InfoRow(label = "Album art", value = info.artworkPublishReport)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun Section(title: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
