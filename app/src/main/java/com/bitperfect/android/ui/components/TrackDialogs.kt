package com.bitperfect.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Everything known about one file, for the Info / Tags readout.
 *
 * A neutral shape on purpose. This dialog is opened from the player, where the
 * facts come from live playback state, and from the library, where they come from
 * a database row; binding it to either one would have meant a second copy.
 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val genre: String = "",
    val composer: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val formatBadge: String = "",
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val durationMs: Long = 0,
    val durationText: String = "",
    val fileSize: Long = 0,
    val folder: String = "",
    val path: String = "",
    /** Cumulative share of the track listened to; may exceed 100. */
    val playedPercent: Int = 0,
    val playedMs: Long = 0,
    /** True when the tags shown were corrected in the app, not read from the file. */
    val isUserEdited: Boolean = false
)

/**
 * Everything known about the current file, including the facts the cleaned-up
 * player no longer shows on screen.
 */
@Composable
fun TrackInfoDialog(
    info: TrackInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info / Tags") },
        text = {
            // A full tag list on a phone screen overflows once the path is
            // included, so the body scrolls rather than pushing the Close button
            // off the bottom.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                InfoLine("Title", info.title)
                InfoLine("Artist", info.artist)
                InfoLine("Album", info.album)
                if (info.albumArtist.isNotBlank() && info.albumArtist != info.artist) {
                    InfoLine("Album artist", info.albumArtist)
                }
                InfoLine("Genre", info.genre)
                InfoLine("Composer", info.composer)
                if (info.year > 0) InfoLine("Year", info.year.toString())
                if (info.trackNumber > 0) InfoLine("Track", info.trackNumber.toString())

                InfoLine("Format", info.formatBadge)
                if (info.sampleRate > 0) InfoLine("Sample rate", "${info.sampleRate} Hz")
                if (info.bitDepth > 0) InfoLine("Bit depth", "${info.bitDepth}-bit")
                if (info.channels > 0) InfoLine("Channels", info.channels.toString())
                if (info.durationMs > 0) {
                    InfoLine(
                        "Duration",
                        info.durationText.ifBlank { formatDuration(info.durationMs) }
                    )
                }
                if (info.fileSize > 0) InfoLine("File size", formatFileSize(info.fileSize))

                // Listening history, shown as both the percentage that drives the
                // "most played" order and the raw time it came from, so the
                // number is checkable rather than mysterious.
                if (info.playedMs > 0) {
                    InfoLine(
                        "Played",
                        "${info.playedPercent}% · ${formatDuration(info.playedMs)} listened"
                    )
                }

                InfoLine("Folder", info.folder)
                // Full path last: it is the longest and the least often wanted,
                // but it is the only way to identify a file unambiguously.
                InfoLine("Path", info.path)

                if (info.isUserEdited) {
                    Text(
                        text = "Tags above were edited in BitPerfect. The file itself " +
                            "still carries its original tags.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Edit the tags a track is filed under.
 *
 * **Library-only, and it says so.** There is no tag writer in the app, so the
 * file on disk keeps its original tags; what changes is how BitPerfect files the
 * track. Claiming to have rewritten the file would be a lie, and quietly
 * rewriting a FLAC badly would be worse.
 */
@Composable
fun EditTrackDetailsDialog(
    info: TrackInfo,
    onSave: (EditedTrackDetails) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(info.title) }
    var artist by remember { mutableStateOf(info.artist) }
    var album by remember { mutableStateOf(info.album) }
    var albumArtist by remember { mutableStateOf(info.albumArtist) }
    var genre by remember { mutableStateOf(info.genre) }
    var year by remember { mutableStateOf(if (info.year > 0) info.year.toString() else "") }
    var trackNumber by remember {
        mutableStateOf(if (info.trackNumber > 0) info.trackNumber.toString() else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Saved in BitPerfect's library. The file's own tags are not " +
                        "changed, and your edit is kept when the library is rescanned.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                TagField("Title", title) { title = it }
                TagField("Artist", artist) { artist = it }
                TagField("Album", album) { album = it }
                TagField("Album artist", albumArtist) { albumArtist = it }
                TagField("Genre", genre) { genre = it }
                Row {
                    TagField(
                        label = "Year",
                        value = year,
                        modifier = Modifier.weight(1f),
                        numeric = true
                    ) { year = it.filter { character -> character.isDigit() }.take(4) }
                    TagField(
                        label = "Track",
                        value = trackNumber,
                        modifier = Modifier.weight(1f),
                        numeric = true
                    ) { trackNumber = it.filter { character -> character.isDigit() }.take(4) }
                }

                if (artist.isBlank() && albumArtist.isBlank()) {
                    // Saying this up front is kinder than the track silently
                    // vanishing from the library after saving.
                    Text(
                        text = "With no artist, this track stays in \"Review unconfirmed " +
                            "music\" rather than the main library.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        EditedTrackDetails(
                            title = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            genre = genre,
                            year = year.toIntOrNull() ?: 0,
                            trackNumber = trackNumber.toIntOrNull() ?: 0
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** The result of [EditTrackDetailsDialog]. */
data class EditedTrackDetails(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int
)

@Composable
private fun TagField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) {
            androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            androidx.compose.foundation.text.KeyboardOptions.Default
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

/**
 * Add, replace or remove a track's lyrics.
 *
 * Accepts LRC — `[mm:ss.cc]` per line — and plain text. Which one it is decides
 * whether the lyrics follow playback, and that is worked out by the same parser
 * that reads sidecar files, so pasted lyrics behave exactly like shipped ones.
 *
 * Saving stores the text in the app, not next to the audio file: writing there
 * needs a consent flow on Android 11+ and is impossible on a read-only volume.
 */
@Composable
fun LyricsEditorDialog(
    trackTitle: String,
    initialLyrics: String,
    hasExistingLyrics: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialLyrics) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics") },
        text = {
            Column {
                Text(
                    text = trackTitle,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Paste LRC for lyrics that follow the music, for example " +
                        "[00:12.50]First line. Plain text works too — it just cannot " +
                        "scroll by itself.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("[00:00.00]…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                // Only offered when there is something to remove, so the button
                // never does nothing.
                if (hasExistingLyrics) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
