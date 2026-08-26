package com.bitperfect.android

import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.PlaybackController

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
 * This is intentionally simple (no DI framework) to keep the app lightweight.
 */
object ServiceLocator {

    /**
     * The single PlaybackController instance, owned by PlaybackService.
     * Set when the Activity binds to the service.
     */
    @Volatile
    var playbackController: PlaybackController? = null

    /**
     * The single NativeAudioEngine instance, owned by PlaybackService.
     * Set when the Activity binds to the service.
     */
    @Volatile
    var engine: NativeAudioEngine? = null

    /**
     * The application-scoped MusicLibrary instance.
     * Set during Application.onCreate() and always available.
     */
    @Volatile
    var musicLibrary: MusicLibrary? = null

    /**
     * Returns true when all service-provided components are available.
     * UI should wait for this before creating ViewModels that depend on
     * the engine or controller.
     */
    fun isReady(): Boolean {
        return playbackController != null && engine != null && musicLibrary != null
    }

    /**
     * Clear service-provided references (called on service disconnect).
     */
    fun clearServiceReferences() {
        playbackController = null
        engine = null
    }
}
