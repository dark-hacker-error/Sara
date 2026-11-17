package com.sara.assistant.phone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.sara.assistant.core.SaraLog

/** A launchable app on the device. */
data class AppEntry(
    val label: String,
    val packageName: String,
)

/**
 * Installed-app index and launcher.
 *
 * QUERY_ALL_PACKAGES is declared in the manifest so the list is complete. The cache is built
 * lazily on first use — enumerating packages is slow enough that we don't want it on the
 * cold-start path.
 */
class AppRegistry(private val context: Context) {

    @Volatile
    private var cache: List<AppEntry>? = null

    fun installedApps(): List<AppEntry> = cache ?: synchronized(this) {
        cache ?: build().also { cache = it }
    }

    /**
     * Resolves a user-spoken app name to a package. Tries, in order:
     * exact label, label prefix, label substring, then package-name substring.
     */
    fun resolve(query: String): AppEntry? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val apps = installedApps()

        apps.firstOrNull { it.label.lowercase() == needle }?.let { return it }
        apps.firstOrNull { it.label.lowercase().startsWith(needle) }?.let { return it }
        apps.firstOrNull { it.label.lowercase().contains(needle) }?.let { return it }
        apps.firstOrNull { it.packageName.lowercase().contains(needle) }?.let { return it }
        return null
    }

    /** Launches by package name, bringing the task to the foreground. */
    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            SaraLog.e("Failed to launch $packageName", it)
            false
        }
    }

    /** Convenience: resolve by spoken name, then launch. */
    fun launchByName(query: String): AppEntry? {
        val entry = resolve(query) ?: return null
        return if (launch(entry.packageName)) entry else null
    }

    private fun build(): List<AppEntry> {
        val manager = context.packageManager
        val installed = runCatching {
            manager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse {
            SaraLog.e("Could not enumerate installed apps", it)
            return emptyList()
        }

        return installed.asSequence()
            .filter { manager.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    label = runCatching { manager.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    packageName = info.packageName,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    @Suppress("unused")
    private fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
