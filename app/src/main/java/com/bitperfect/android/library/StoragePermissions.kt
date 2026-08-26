package com.bitperfect.android.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Resolves and checks the runtime permission needed to read on-device audio.
 *
 * Android 13 (API 33) replaced broad storage access with the scoped
 * READ_MEDIA_AUDIO permission. Both are declared in the manifest with the
 * legacy one capped at API 32.
 */
object StoragePermissions {

    /**
     * The permission this device requires to enumerate audio files.
     */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Notification permission, required from Android 13 for playback controls.
     * Null on older releases where notifications need no runtime grant.
     */
    val notificationPermission: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    /**
     * Whether audio files can currently be read.
     */
    fun hasAudioAccess(context: Context): Boolean = isGranted(context, requiredPermission)

    /**
     * Whether playback notifications can currently be posted.
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val permission = notificationPermission ?: return true
        return isGranted(context, permission)
    }

    /**
     * Permissions that still need to be requested, in the order to request them.
     */
    fun missingPermissions(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (!hasAudioAccess(context)) missing.add(requiredPermission)
        notificationPermission?.let { permission ->
            if (!isGranted(context, permission)) missing.add(permission)
        }
        return missing.toTypedArray()
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
