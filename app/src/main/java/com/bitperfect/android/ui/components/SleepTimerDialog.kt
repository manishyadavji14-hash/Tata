package com.bitperfect.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * SleepTimerDialog - Dialog for setting or cancelling the sleep timer.
 *
 * Features:
 * - Preset duration options: 15min, 30min, 45min, 1h, 1.5h, 2h
 * - Custom duration input (minutes)
 * - Cancel Timer option when a timer is active
 * - Shows remaining time when a timer is active
 */
@Composable
fun SleepTimerDialog(
    isTimerActive: Boolean,
    remainingMs: Long?,
    onSetTimer: (Long) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    var showCustomInput by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }

    val presets = listOf(
        "15 min" to 15L * 60 * 1000,
        "30 min" to 30L * 60 * 1000,
        "45 min" to 45L * 60 * 1000,
        "1 hour" to 60L * 60 * 1000,
        "1.5 hours" to 90L * 60 * 1000,
        "2 hours" to 120L * 60 * 1000
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Sleep Timer")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Show remaining time if timer is active
                if (isTimerActive && remainingMs != null) {
                    val remainingMinutes = (remainingMs / 60000).toInt()
                    val remainingSeconds = ((remainingMs % 60000) / 1000).toInt()
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Timer active: ${remainingMinutes}m ${remainingSeconds}s remaining",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Cancel timer option
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCancelTimer()
                                onDismiss()
                            },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cancel Timer",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Or set a new timer:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Preset durations
                presets.forEach { (label, durationMs) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSetTimer(durationMs)
                                onDismiss()
                            },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Custom duration
                if (showCustomInput) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { customMinutes = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val minutes = customMinutes.toLongOrNull()
                                if (minutes != null && minutes > 0) {
                                    onSetTimer(minutes * 60 * 1000)
                                    onDismiss()
                                }
                            }
                        ) {
                            Text("Set")
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomInput = true },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Custom duration...",
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Format remaining sleep timer time for display.
 */
fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60000).toInt()
    val seconds = ((remainingMs % 60000) / 1000).toInt()
    return when {
        totalMinutes >= 60 -> {
            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            "${hours}h ${mins}m"
        }
        totalMinutes > 0 -> "${totalMinutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
