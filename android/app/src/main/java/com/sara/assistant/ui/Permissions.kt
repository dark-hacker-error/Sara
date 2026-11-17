package com.sara.assistant.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sara.assistant.phone.SaraAccessibilityService

/** Snapshot of everything Sara needs from the user. */
data class PermissionSnapshot(
    val microphone: Boolean = false,
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
) {
    val allGranted: Boolean
        get() = microphone && accessibility && overlay && notifications
}

/**
 * Reads permission state and builds the "take me there" intents for the onboarding flow.
 * Accessibility and overlay have no runtime dialog — the only path is the settings screen,
 * so the UI has to deep-link.
 */
object Permissions {

    fun snapshot(context: Context): PermissionSnapshot = PermissionSnapshot(
        microphone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        accessibility = isAccessibilityEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    /**
     * Checks the enabled-services list rather than [SaraAccessibilityService.instance], because
     * the service only binds once the system decides to start it. The list is the source of
     * truth the Settings toggle writes to.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        if (SaraAccessibilityService.isRunning) return true
        val expected = ComponentName(context, SaraAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun notificationSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
}
