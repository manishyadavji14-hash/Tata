package com.bitperfect.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * Third-party attribution screen.
 *
 * Mirrors the LICENSES.md file at the repository root. The data lives here as
 * structured values rather than bundled markdown so entries can carry tappable
 * source links, and so a missing asset can never leave the screen blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "BitPerfect's audio pipeline is original work built from " +
                        "public specifications. The libraries below are used under " +
                        "their respective licenses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { SectionHeading("Libraries") }

            items(THIRD_PARTY_LIBRARIES) { entry ->
                LicenseCard(
                    entry = entry,
                    onOpenSource = { url -> context.openUrl(url) }
                )
            }

            item { SectionHeading("Specifications implemented") }

            items(SPECIFICATIONS) { spec ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = spec.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = spec.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { SectionHeading("Apache License 2.0") }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = APACHE_2_SUMMARY,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            item { SectionHeading("BSD 3-Clause (Google Test)") }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = BSD_3_CLAUSE_SUMMARY,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun LicenseCard(
    entry: LicenseEntry,
    onOpenSource: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = entry.license,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.usage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.copyright != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.copyright,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
            TextButton(onClick = { onOpenSource(entry.sourceUrl) }) {
                Text("View source")
                Spacer(modifier = Modifier.height(0.dp))
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/**
 * Opens a URL, ignoring the case where no browser is installed rather than
 * crashing the settings flow.
 */
private fun android.content.Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser available; nothing useful to do here.
    }
}

private data class LicenseEntry(
    val name: String,
    val license: String,
    val usage: String,
    val sourceUrl: String,
    val copyright: String? = null
)

private data class SpecEntry(
    val name: String,
    val detail: String
)

private const val APACHE_2 = "Apache License 2.0"

private val THIRD_PARTY_LIBRARIES = listOf(
    LicenseEntry(
        name = "Kotlin Standard Library",
        license = APACHE_2,
        usage = "Core language runtime",
        sourceUrl = "https://github.com/JetBrains/kotlin",
        copyright = "Copyright 2010-2024 JetBrains s.r.o."
    ),
    LicenseEntry(
        name = "Kotlin Coroutines",
        license = APACHE_2,
        usage = "Asynchronous work for scanning and I/O",
        sourceUrl = "https://github.com/Kotlin/kotlinx.coroutines",
        copyright = "Copyright 2016-2024 JetBrains s.r.o."
    ),
    LicenseEntry(
        name = "AndroidX Libraries",
        license = APACHE_2,
        usage = "Core compatibility, lifecycle and architecture components",
        sourceUrl = "https://github.com/androidx/androidx",
        copyright = "Copyright The Android Open Source Project"
    ),
    LicenseEntry(
        name = "Jetpack Compose",
        license = APACHE_2,
        usage = "Declarative UI toolkit and navigation",
        sourceUrl = "https://github.com/androidx/androidx",
        copyright = "Copyright The Android Open Source Project"
    ),
    LicenseEntry(
        name = "Material 3",
        license = APACHE_2,
        usage = "Design system components and theming",
        sourceUrl = "https://github.com/material-components/material-components-android",
        copyright = "Copyright Google LLC"
    ),
    LicenseEntry(
        name = "Room",
        license = APACHE_2,
        usage = "SQLite persistence for the music library",
        sourceUrl = "https://github.com/androidx/androidx",
        copyright = "Copyright The Android Open Source Project"
    ),
    LicenseEntry(
        name = "AndroidX DataStore",
        license = APACHE_2,
        usage = "Persisting settings, including the equalizer curve",
        sourceUrl = "https://github.com/androidx/androidx",
        copyright = "Copyright The Android Open Source Project"
    ),
    LicenseEntry(
        name = "Coil",
        license = APACHE_2,
        usage = "Album artwork loading and image caching",
        sourceUrl = "https://github.com/coil-kt/coil",
        copyright = "Copyright Coil Contributors"
    ),
    LicenseEntry(
        name = "AndroidX Media and Media3",
        license = APACHE_2,
        usage = "Media session and playback notification integration",
        sourceUrl = "https://github.com/androidx/media",
        copyright = "Copyright The Android Open Source Project"
    ),
    LicenseEntry(
        name = "Guava",
        license = APACHE_2,
        usage = "ListenableFuture, required by the media session APIs",
        sourceUrl = "https://github.com/google/guava",
        copyright = "Copyright Google LLC"
    ),
    LicenseEntry(
        name = "Google Test",
        license = "BSD 3-Clause",
        usage = "Native C++ unit tests. Test builds only, not in the release APK.",
        sourceUrl = "https://github.com/google/googletest",
        copyright = "Copyright Google LLC"
    )
)

private val SPECIFICATIONS = listOf(
    SpecEntry(
        name = "USB Audio Class 1.0 and 2.0",
        detail = "Descriptor parsing, endpoint configuration, sample rate " +
            "negotiation and isochronous transfer management. Public " +
            "specifications from the USB Implementers Forum."
    ),
    SpecEntry(
        name = "DSF file format",
        detail = "DSF header parsing and DSD data extraction. Public " +
            "specification published by Sony."
    ),
    SpecEntry(
        name = "DoP (DSD over PCM) 1.1",
        detail = "DSD to DoP frame encoding with 0x05/0xFA marker alternation. " +
            "Open standard published by dCS."
    ),
    SpecEntry(
        name = "WAV / RIFF",
        detail = "WAV header parsing and PCM extraction. Public Microsoft and " +
            "IBM specification."
    ),
    SpecEntry(
        name = "FLAC",
        detail = "FLAC stream parsing and decoding. Open format specification " +
            "from Xiph.Org."
    )
)

private val APACHE_2_SUMMARY = """
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
""".trimIndent()

private val BSD_3_CLAUSE_SUMMARY = """
    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

    1. Redistributions of source code must retain the above copyright notice,
       this list of conditions and the following disclaimer.
    2. Redistributions in binary form must reproduce the above copyright notice,
       this list of conditions and the following disclaimer in the documentation
       and/or other materials provided with the distribution.
    3. Neither the name of the copyright holder nor the names of its
       contributors may be used to endorse or promote products derived from
       this software without specific prior written permission.
""".trimIndent()
