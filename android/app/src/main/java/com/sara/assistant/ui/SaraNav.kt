package com.sara.assistant.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sara.assistant.ui.home.HomeScreen
import com.sara.assistant.ui.onboarding.OnboardingScreen
import com.sara.assistant.ui.settings.SettingsScreen

/** Permission actions the screens can trigger; owned by the Activity. */
data class PermissionActions(
    val requestMicrophone: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
    val openOverlay: () -> Unit,
    val openNotificationSettings: () -> Unit,
)

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

@Composable
fun SaraApp(
    viewModel: SaraViewModel,
    permissionActions: PermissionActions,
    navController: NavHostController = rememberNavController(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val backend by viewModel.backendConnection.collectAsStateWithLifecycle()
    val modelFetch by viewModel.modelFetch.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val bubbleVisible by viewModel.bubbleVisible.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        when (current) {
            is Notice.Text -> snackbarHostState.showSnackbar(current.message)
        }
        viewModel.consumeNotice()
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    permissions = permissions,
                    apiKey = settings.live.geminiApiKey,
                    onApiKeyChange = { value -> viewModel.updateLive { it.copy(geminiApiKey = value) } },
                    onRequestMicrophone = permissionActions.requestMicrophone,
                    onRequestNotifications = permissionActions.requestNotifications,
                    onOpenAccessibility = permissionActions.openAccessibility,
                    onOpenOverlay = permissionActions.openOverlay,
                    onFinish = {
                        viewModel.markOnboardingComplete()
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    state = liveState,
                    permissions = permissions,
                    backend = backend,
                    bubbleVisible = bubbleVisible,
                    onStart = viewModel::startVoiceSession,
                    onStopSession = viewModel::stopVoiceSession,
                    onStopEverything = viewModel::stopEverything,
                    onBubbleToggle = viewModel::toggleBubble,
                    onSendText = viewModel::sendText,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    permissions = permissions,
                    backend = backend,
                    modelFetch = modelFetch,
                    onBack = { navController.popBackStack() },
                    onUpdateLive = viewModel::updateLive,
                    onUpdateBackendUrl = viewModel::updateBackendUrl,
                    onUpsertAgent = viewModel::upsertAgent,
                    onRemoveAgent = viewModel::removeAgent,
                    onResetAgents = viewModel::resetAgents,
                    onFetchModels = viewModel::fetchModels,
                    onClearModelFetch = viewModel::clearModelFetch,
                    onBubbleToggle = viewModel::setBubble,
                    onOpenAccessibility = permissionActions.openAccessibility,
                    onOpenOverlay = permissionActions.openOverlay,
                    onOpenNotificationSettings = permissionActions.openNotificationSettings,
                )
            }
        }
    }
}
