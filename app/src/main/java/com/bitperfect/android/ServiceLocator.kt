package com.bitperfect.android

import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.PlaybackController
import java.util.concurrent.atomic.AtomicReference

/**
 * ServiceLocator - Application-scoped singleton for shared component access.
 *
 * Provides one place to reach the shared audio components, so nothing has to be
 * threaded through deep Compose trees and no second engine gets constructed.
 *
 * Lifecycle:
 * - `musicLibrary` is set during Application.onCreate(), so it is always available.
 * - `playbackController` and `engine` are set by MainActivity once the retained
 *   PlayerViewModel that owns them has been created. They are NOT owned by
 *   PlaybackService: the service is only started when playback begins, and the
 *   UI must work before that.
 * - PlayerViewModel.onCleared() clears them, because it owns them. Clearing from
 *   the Activity would drop the reference on every rotation while the engine
 *   behind it is still alive.
 *
 * Thread-safety: Uses a single AtomicReference to an immutable holder to guarantee
 * that consumers never observe a partial state (e.g., engine set but controller null).
 *
 * This is intentionally simple (no DI framework) to keep the app lightweight.
 */
object ServiceLocator {

    /**
     * Immutable snapshot of all service-provided components.
     * Either all fields are populated (ready) or none are (not ready).
     */
    data class ServiceComponents(
        val playbackController: PlaybackController? = null,
        val engine: NativeAudioEngine? = null,
        val musicLibrary: MusicLibrary? = null
    ) {
        /** Returns true when all components are available. */
        val isReady: Boolean
            get() = playbackController != null && engine != null && musicLibrary != null
    }

    private val componentsRef = AtomicReference(ServiceComponents())

    /**
     * Plain-language state of the current track's cover on the media session.
     *
     * Lives here rather than on a component because the writer is PlaybackService
     * and the reader is the player UI, and neither holds the other. A string
     * because it is only ever displayed.
     *
     * It exists because the lock screen is the one surface whose failures cannot be
     * seen from inside the app, and the maintainer works from a phone with no way
     * to read a log — so without this, "no cover on the lock screen" can only be
     * guessed at. It was guessed at three times.
     */
    val artworkPublishReport = AtomicReference("Nothing playing")

    /**
     * The single PlaybackController instance, owned by PlaybackService.
     */
    val playbackController: PlaybackController?
        get() = componentsRef.get().playbackController

    /**
     * The single NativeAudioEngine instance, owned by PlaybackService.
     */
    val engine: NativeAudioEngine?
        get() = componentsRef.get().engine

    /**
     * The application-scoped MusicLibrary instance.
     */
    val musicLibrary: MusicLibrary?
        get() = componentsRef.get().musicLibrary

    /**
     * Returns true when every shared component is available.
     */
    fun isReady(): Boolean {
        return componentsRef.get().isReady
    }

    /**
     * Set all shared components atomically.
     * Called by MainActivity once the retained PlayerViewModel exists.
     */
    fun setServiceComponents(
        playbackController: PlaybackController,
        engine: NativeAudioEngine,
        musicLibrary: MusicLibrary
    ) {
        componentsRef.set(ServiceComponents(
            playbackController = playbackController,
            engine = engine,
            musicLibrary = musicLibrary
        ))
    }

    /**
     * Set only the music library (available before service bind).
     * Called during Application.onCreate().
     * Uses compareAndSet loop for atomic read-modify-write.
     */
    fun setMusicLibrary(musicLibrary: MusicLibrary) {
        while (true) {
            val current = componentsRef.get()
            val updated = current.copy(musicLibrary = musicLibrary)
            if (componentsRef.compareAndSet(current, updated)) break
        }
    }

    /**
     * Clear the engine and controller references. Called from
     * PlayerViewModel.onCleared(), which owns them.
     * Uses compareAndSet loop for atomic read-modify-write.
     */
    fun clearServiceReferences() {
        while (true) {
            val current = componentsRef.get()
            val updated = current.copy(
                playbackController = null,
                engine = null
            )
            if (componentsRef.compareAndSet(current, updated)) break
        }
    }
}
