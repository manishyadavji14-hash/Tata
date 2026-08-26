package com.bitperfect.android

import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.PlaybackController
import java.util.concurrent.atomic.AtomicReference

/**
 * ServiceLocator - Application-scoped singleton for shared component access.
 *
 * Provides centralized access to components owned by PlaybackService,
 * avoiding the need to pass service references through deep Compose trees.
 *
 * Lifecycle:
 * - musicLibrary is set during Application.onCreate() (always available)
 * - playbackController and engine are set when MainActivity binds to PlaybackService
 * - All nullable references are cleared when the service unbinds
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
     * Returns true when all service-provided components are available.
     * UI should wait for this before creating ViewModels that depend on
     * the engine or controller.
     */
    fun isReady(): Boolean {
        return componentsRef.get().isReady
    }

    /**
     * Set all service-provided components atomically.
     * Called when the Activity binds to PlaybackService.
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
     * Clear service-provided references (called on service disconnect).
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
