package com.bitperfect.android.service

/**
 * How a stored artwork reference can be handed to the system UI.
 *
 * The library stores artwork as a single nullable string that is really one of
 * two incompatible things, and the difference decides whether the lock screen can
 * display it:
 *
 * - a `content://media/external/audio/albumart/...` URI, from MediaStore. World
 *   readable, so it can be given to the media session and the notification as a
 *   URI and the system will load it.
 * - an absolute path into the app's own cache, written by `ArtworkCache` when the
 *   cover had to be extracted from the file. **Not** readable by SystemUI: the app
 *   is the only process allowed in there. Handing over a `file://` URI to it
 *   produces a silently blank cover, which is exactly the bug this type exists to
 *   prevent — the bytes have to be read here and passed by value instead.
 *
 * Splitting the decision out keeps it unit-testable, since everything downstream
 * of it needs a real `Bitmap` and a device.
 */
sealed interface ArtworkSource {

    /** A URI the system can read for itself. */
    data class SystemReadableUri(val uri: String) : ArtworkSource

    /** A file only this process can read; its bytes must be sent by value. */
    data class AppPrivateFile(val path: String) : ArtworkSource

    /** No artwork recorded. */
    data object None : ArtworkSource

    companion object {
        /**
         * Schemes the system resolves on its own behalf.
         *
         * `file` is deliberately absent. A file URI is only readable if the file
         * itself is, and the only files this app points at are in its private
         * cache — so treating them as system-readable is what makes covers vanish.
         */
        private val SYSTEM_READABLE_SCHEMES = listOf(
            "content://",
            "android.resource://",
            "http://",
            "https://"
        )

        /** Classify a stored `Track.artworkPath` / `TrackDetails.artworkUri`. */
        fun of(artworkPath: String?): ArtworkSource {
            val value = artworkPath?.trim().orEmpty()
            if (value.isEmpty()) return None

            if (SYSTEM_READABLE_SCHEMES.any { value.startsWith(it, ignoreCase = true) }) {
                return SystemReadableUri(value)
            }

            // A bare absolute path, which is what ArtworkCache hands back.
            if (value.startsWith("/")) return AppPrivateFile(value)

            // A file:// URI still points at a private file; strip the scheme so it
            // is read rather than forwarded.
            if (value.startsWith("file://", ignoreCase = true)) {
                val path = value.removePrefix("file://")
                return if (path.startsWith("/")) AppPrivateFile(path) else None
            }

            // Anything else is not something that can be resolved reliably.
            return None
        }
    }
}
