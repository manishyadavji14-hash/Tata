package com.bitperfect.android.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.theme.BitPerfectGreen
import com.bitperfect.android.ui.theme.ErrorRed

/**
 * DacDiagnosticsScreen - USB DAC information and diagnostics display.
 *
 * Shows:
 * - Manufacturer, Product name
 * - VID/PID (hexadecimal)
 * - UAC version (1 or 2)
 * - Audio interfaces list with alternate settings
 * - Endpoint info (address, direction, type, maxPacketSize)
 * - Supported sample rates list
 * - Supported formats (bit depths)
 * - Current rate, current mode
 * - Clock information (for UAC2)
 * - Error/underrun counters
 * - Real-time buffer fill indicator
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DacDiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBackClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DAC Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (!uiState.isDeviceConnected) {
            NoDeviceView(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Device Information
                item {
                    SectionHeader("Device Information")
                }
                item {
                    DeviceInfoCard(uiState)
                }

                // USB Identifiers
                item {
                    SectionHeader("USB Identifiers")
                }
                item {
                    UsbIdentifiersCard(uiState)
                }

                // Audio Interfaces
                item {
                    SectionHeader("Audio Interfaces")
                }
                items(uiState.interfaces) { iface ->
                    InterfaceCard(iface)
                }

                // Endpoints
                item {
                    SectionHeader("Endpoints")
                }
                items(uiState.endpoints) { endpoint ->
                    EndpointCard(endpoint)
                }

                // Supported Formats
                item {
                    SectionHeader("Supported Formats")
                }
                item {
                    SupportedFormatsCard(uiState)
                }

                // Current State
                item {
                    SectionHeader("Current State")
                }
                item {
                    CurrentStateCard(uiState)
                }

                // Clock Information
                if (uiState.uacVersion >= 2) {
                    item {
                        SectionHeader("Clock Information (UAC2)")
                    }
                    item {
                        ClockInfoCard(uiState)
                    }
                }

                // Buffer Status
                item {
                    SectionHeader("Buffer Status")
                }
                item {
                    BufferStatusCard(uiState)
                }

                // Error Counters
                item {
                    SectionHeader("Error Counters")
                }
                item {
                    ErrorCountersCard(uiState)
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NoDeviceView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Usb,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No USB DAC Connected",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Connect a USB audio device to see diagnostics",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
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
private fun DeviceInfoCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    DiagCard {
        DiagRow("Manufacturer", state.manufacturer)
        DiagRow("Product", state.productName)
        DiagRow("Serial Number", state.serialNumber.ifEmpty { "N/A" })
        DiagRow("UAC Version", "UAC ${state.uacVersion}")
    }
}

@Composable
private fun UsbIdentifiersCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    DiagCard {
        DiagRow("Vendor ID (VID)", "0x${state.vendorId.toString(16).uppercase().padStart(4, '0')}")
        DiagRow("Product ID (PID)", "0x${state.productId.toString(16).uppercase().padStart(4, '0')}")
        DiagRow("USB Version", state.usbVersion)
        DiagRow("Device Class", "Audio (0x01)")
    }
}

@Composable
private fun InterfaceCard(iface: DiagnosticsViewModel.AudioInterface) {
    DiagCard {
        DiagRow("Interface #", "${iface.interfaceNumber}")
        DiagRow("Subclass", iface.subclass)
        DiagRow("Alternate Settings", "${iface.alternateSettings}")
        DiagRow("Protocol", iface.protocol)
        if (iface.terminalType.isNotEmpty()) {
            DiagRow("Terminal Type", iface.terminalType)
        }
    }
}

@Composable
private fun EndpointCard(endpoint: DiagnosticsViewModel.EndpointInfo) {
    DiagCard {
        DiagRow("Address", "0x${endpoint.address.toString(16).uppercase().padStart(2, '0')}")
        DiagRow("Direction", endpoint.direction)
        DiagRow("Transfer Type", endpoint.transferType)
        DiagRow("Max Packet Size", "${endpoint.maxPacketSize} bytes")
        DiagRow("Interval", "${endpoint.interval} ms")
        if (endpoint.syncType.isNotEmpty()) {
            DiagRow("Sync Type", endpoint.syncType)
        }
    }
}

@Composable
private fun SupportedFormatsCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    DiagCard {
        // Sample rates
        DiagRow("Sample Rates", state.supportedSampleRates.joinToString(", ") { "${it / 1000.0} kHz" })
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        // Bit depths
        DiagRow("Bit Depths", state.supportedBitDepths.joinToString(", ") { "${it}-bit" })
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        // Channels
        DiagRow("Channels", "${state.maxChannels}")
    }
}

@Composable
private fun CurrentStateCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    DiagCard {
        DiagRow("Current Sample Rate", if (state.currentSampleRate > 0) "${state.currentSampleRate / 1000.0} kHz" else "N/A")
        DiagRow("Current Bit Depth", if (state.currentBitDepth > 0) "${state.currentBitDepth}-bit" else "N/A")
        DiagRow("Current Mode", state.currentMode)
        DiagRow("Active Interface", if (state.activeInterface >= 0) "#${state.activeInterface}" else "None")
        DiagRow("Active Alt Setting", if (state.activeAltSetting >= 0) "#${state.activeAltSetting}" else "None")
    }
}

@Composable
private fun ClockInfoCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    DiagCard {
        DiagRow("Clock Source", state.clockSource)
        DiagRow("Clock Frequency", if (state.clockFrequency > 0) "${state.clockFrequency} Hz" else "N/A")
        DiagRow("Clock Validity", if (state.clockValid) "Valid" else "Invalid")
        DiagRow("SOF Sync", if (state.sofSyncSupported) "Supported" else "Not Supported")
    }
}

@Composable
private fun BufferStatusCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    DiagCard {
        Column {
            DiagRow("Buffer Fill", "${(state.bufferFillLevel * 100).toInt()}%")
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.bufferFillLevel },
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    state.bufferFillLevel < 0.2f -> ErrorRed
                    state.bufferFillLevel < 0.5f -> MaterialTheme.colorScheme.primary
                    else -> BitPerfectGreen
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiagRow("Buffer Size", "${state.bufferSizeMs} ms")
            DiagRow("Latency", "${state.latencyMs} ms")
            DiagRow("Total Transferred", formatBytes(state.totalBytesTransferred))
        }
    }
}

@Composable
private fun ErrorCountersCard(state: DiagnosticsViewModel.DiagnosticsUiState) {
    val hasErrors = state.underrunCount > 0 || state.overrunCount > 0 || state.transferErrors > 0

    DiagCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (hasErrors) Icons.Default.Error else Icons.Default.Info,
                contentDescription = null,
                tint = if (hasErrors) ErrorRed else BitPerfectGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (hasErrors) "Errors Detected" else "No Errors",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasErrors) ErrorRed else BitPerfectGreen,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        DiagRow("Underruns", "${state.underrunCount}")
        DiagRow("Overruns", "${state.overrunCount}")
        DiagRow("Transfer Errors", "${state.transferErrors}")
        DiagRow("USB Resets", "${state.usbResets}")
    }
}

@Composable
private fun DiagCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
