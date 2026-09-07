package com.bitperfect.android.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitperfect.android.library.StoragePermissions

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
    onDismiss: () -> Unit,
    onRequestUsbAccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val notificationsAllowed = remember { StoragePermissions.hasNotificationAccess(context) }

    // Offered whenever a DAC is not claimed, because that is the only state this can
    // help with. Nothing prompts for USB access on its own any more — attaching a DAC
    // already makes Android show its app-chooser, and asking as well put two dialogs
    // on screen at the same moment. This is the way back if that dialog was dismissed.
    val canOfferUsbAccess = !info.isUsbDeviceAttached

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

                // Always shown, including when there is no DAC. "Bit-perfect: No"
                // above is the whole reason someone opens this panel, and until this
                // row existed there was nothing anywhere in the app that could tell
                // "no DAC" apart from "DAC here and it could not be claimed".
                Section("USB DAC")

                // Recomputed from the engine every time this panel opens, so it
                // follows the track being played. It used to show the last attach
                // event, which meant a message about one unsupported file stayed on
                // screen as the apparent verdict on every file after it.
                InfoRow("Status", info.usbDacReport)
                InfoRow(
                    label = "Claimed by engine",
                    value = if (info.isUsbDeviceAttached) "Yes" else "No"
                )

                // Only ever present when a DAC is attached and this track's format has
                // no exact decoder. Shown because the badge above then honestly reads
                // "Android output" with a DAC plugged in, which otherwise looks exactly
                // like the DAC not having been claimed at all.
                info.usbBypassReason?.let { reason ->
                    InfoRow("Not using the DAC", reason)
                }

                // Labelled as the *last configured* stream on purpose: these come
                // from the engine's current configuration, which is whatever was set
                // up for the last track that got as far as configuring. Shown as
                // "Stream" it read like a description of the track on screen, which
                // is how a 44.1 kHz packet size came to be displayed above a
                // 48 kHz file.
                info.usbDetail?.let { detail ->
                    InfoRow("Last configured stream", detail)
                }

                InfoRow("Last USB event", info.usbLastEvent)

                // Placed here rather than in the dialog's button row, which already
                // carries the notification "Allow" and can only hold one action.
                if (canOfferUsbAccess) {
                    TextButton(
                        onClick = onRequestUsbAccess,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("Ask Android for DAC access")
                    }
                }

                // Transport facts only once there is a transport, so the panel never
                // implies one that is not in use.
                if (info.isUsbOutputActive || info.transportName != null) {
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

                // Checked here rather than passed in, because it can change while
                // this dialog is open — the user may go and grant it and come back.
                //
                // Reported at all because without it a denied permission is
                // completely silent: no notification, no lock-screen controls, and
                // nothing anywhere in the app saying why. It reads exactly like the
                // feature is broken. The permission is requested at first launch, so
                // the way it ends up denied is a reinstall, where the prompt is easy
                // to dismiss and never appears again.
                if (notificationsAllowed) {
                    InfoRow(label = "Album art", value = info.artworkPublishReport)
                } else {
                    InfoRow(
                        label = "Notifications",
                        value = "Blocked — Android is not allowing any notification, " +
                            "so nothing can appear in the shade or on the lock screen. " +
                            "Album art cannot be judged until this is allowed."
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        // Offered only when it would do something, and it goes straight to the
        // screen that fixes it rather than describing where to find it.
        dismissButton = if (notificationsAllowed) {
            null
        } else {
            { TextButton(onClick = { openNotificationSettings(context) }) { Text("Allow") } }
        }
    )
}

/** Open this app's notification settings, falling back to its app details page. */
private fun openNotificationSettings(context: Context) {
    val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))

    // Some vendor builds do not implement the notification screen; landing on the
    // app's details page is still one tap from the same switch.
    for (intent in listOf(appNotifications, appDetails)) {
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
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
