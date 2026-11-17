package com.sara.assistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.sara.assistant.core.SaraLog
import com.sara.assistant.ui.PermissionActions
import com.sara.assistant.ui.Permissions
import com.sara.assistant.ui.SaraApp
import com.sara.assistant.ui.SaraViewModel
import com.sara.assistant.ui.theme.SaraTheme
import kotlinx.coroutines.launch

/**
 * Single-activity host. Also the place where runtime permissions are requested — Compose
 * screens only ever get lambdas, never the Activity.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as SaraApp).container }

    private val viewModel: SaraViewModel by viewModels {
        SaraViewModel.Factory(container)
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPermissions()
        if (!granted) suggest("Microphone permission is required for Sara to hear you.")
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The key may need migrating to EncryptedSharedPreferences; doing it off the main
        // thread keeps first launch smooth.
        lifecycleScope.launch {
            runCatching { container.settings.current }
                .onFailure { SaraLog.e("Settings load failed", it) }
        }

        setContent {
            SaraTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF05060A),
                                    Color(0xFF0A0C14),
                                    Color(0xFF120E22),
                                ),
                            ),
                        ),
                ) {
                    SaraApp(
                        viewModel = viewModel,
                        permissionActions = PermissionActions(
                            requestMicrophone = ::requestMicrophone,
                            requestNotifications = ::requestNotifications,
                            openAccessibility = {
                                startActivitySafely(Permissions.accessibilitySettingsIntent())
                            },
                            openOverlay = {
                                startActivitySafely(Permissions.overlaySettingsIntent(this@MainActivity))
                            },
                            openNotificationSettings = {
                                startActivitySafely(Permissions.notificationSettingsIntent(this@MainActivity))
                            },
                        ),
                    )
                }
            }
        }

        maybeStartVoiceFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeStartVoiceFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system settings screen is the usual way permissions land.
        viewModel.refreshPermissions()
    }

    private fun maybeStartVoiceFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) {
            viewModel.startVoiceSession()
        }
    }

    private fun requestMicrophone() {
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.refreshPermissions()
        }
    }

    private fun startActivitySafely(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { error ->
                SaraLog.e("Could not open settings screen", error)
                suggest("Could not open that settings screen on this device.")
            }
    }

    private fun suggest(message: String) {
        SaraLog.w(message)
    }

    companion object {
        const val EXTRA_START_VOICE = "com.sara.assistant.extra.START_VOICE"
    }
}
