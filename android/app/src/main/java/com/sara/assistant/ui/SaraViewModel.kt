package com.sara.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sara.assistant.agent.RouterEvent
import com.sara.assistant.backend.BackendClient
import com.sara.assistant.core.SaraContainer
import com.sara.assistant.core.SaraLog
import com.sara.assistant.data.AgentConfig
import com.sara.assistant.data.LiveSettings
import com.sara.assistant.data.ModelCatalogClient
import com.sara.assistant.data.ModelInfo
import com.sara.assistant.data.ModelListResult
import com.sara.assistant.live.LiveState
import com.sara.assistant.live.SaraUiState
import com.sara.assistant.live.StepStatus
import com.sara.assistant.service.OverlayBubbleService
import com.sara.assistant.service.SaraForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Result of a model-list fetch, for the settings dropdown. */
sealed interface ModelFetchState {
    data object Loading : ModelFetchState
    class Loaded(val models: List<ModelInfo>) : ModelFetchState
    class Failed(val message: String) : ModelFetchState
}

/** One-shot messages shown as a snackbar. */
sealed interface Notice {
    class Text(val message: String) : Notice
}

/**
 * The only place UI touches the voice session, the backend and settings.
 *
 * Permission checks live here too, so every entry point (home screen, overlay bubble,
 * notification action) goes through the same gate before the mic opens.
 */
class SaraViewModel(private val container: SaraContainer) : ViewModel() {

    val liveState: StateFlow<SaraUiState> = container.live.state
    val settings: StateFlow<com.sara.assistant.data.SaraSettings> = container.settings.settings
    val backendConnection: StateFlow<BackendClient.ConnectionState> = container.backend.connection

    private val _permissions = MutableStateFlow(Permissions.snapshot(container.context))
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _modelFetch = MutableStateFlow<ModelFetchState?>(null)
    val modelFetch: StateFlow<ModelFetchState?> = _modelFetch.asStateFlow()

    private val _bubbleVisible = MutableStateFlow(false)
    val bubbleVisible: StateFlow<Boolean> = _bubbleVisible.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val modelCatalog = ModelCatalogClient()

    init {
        viewModelScope.launch {
            container.router.events.collect { event -> onRouterEvent(event) }
        }
    }

    private fun onRouterEvent(event: RouterEvent) {
        when (event) {
            is RouterEvent.TaskStarted -> container.live.beginTask(event.goal)

            is RouterEvent.Step -> container.live.pushStep(
                agent = event.agent,
                detail = event.detail,
                status = when (event.status) {
                    "done" -> StepStatus.DONE
                    "failed" -> StepStatus.FAILED
                    else -> StepStatus.RUNNING
                },
            )

            RouterEvent.TaskFinished -> container.live.endTask()

            is RouterEvent.TaskFailed -> {
                container.live.reportError(event.message)
                container.live.endTask()
            }

            is RouterEvent.ConfirmationNeeded -> {
                container.live.pushStep("safety", event.reason, StepStatus.RUNNING)
                _notice.value = Notice.Text("Sara needs your spoken confirmation.")
            }

            is RouterEvent.Notice -> _notice.value = Notice.Text(event.message)
        }
    }

    // ---------------------------------------------------------------- session

    fun refreshPermissions() {
        _permissions.value = Permissions.snapshot(container.context)
    }

    /** Opens the mic only when the user has granted what's needed. */
    fun startVoiceSession() {
        refreshPermissions()
        val snapshot = _permissions.value
        if (!snapshot.microphone) {
            _notice.value = Notice.Text("Sara needs microphone access first.")
            return
        }
        if (container.settings.current.live.geminiApiKey.isBlank()) {
            _notice.value = Notice.Text("Add your Gemini API key in Settings first.")
            return
        }

        container.router.resumeAfterStop()
        SaraForegroundService.start(container.context)
        container.backend.connect()
        container.live.start()
    }

    fun stopVoiceSession() {
        container.live.stop()
        container.backend.disconnect()
        SaraForegroundService.stop(container.context)
    }

    /** The Stop button: aborts every running task and silences Sara immediately. */
    fun stopEverything() {
        container.router.requestStop()
        container.live.bargeIn()
        container.safety.requestStop()
    }

    fun sendText(text: String) {
        if (liveState.value.liveState == LiveState.IDLE) startVoiceSession()
        container.live.sendText(text)
    }

    // ---------------------------------------------------------------- settings

    fun updateLive(transform: (LiveSettings) -> LiveSettings) {
        container.settings.updateLive(transform)
        // Live settings (model, voice, prompt) only take effect on a new session.
        if (container.live.state.value.liveState != LiveState.IDLE) {
            _notice.value = Notice.Text("Applied. Restart the session to use the new settings.")
        }
    }

    fun updateBackendUrl(url: String) {
        container.settings.update { it.copy(backendUrl = url.trim()) }
        container.backend.disconnect()
    }

    fun upsertAgent(agent: AgentConfig) {
        container.settings.upsertAgent(agent)
        container.backend.pushConfig()
    }

    fun removeAgent(id: String) {
        container.settings.removeAgent(id)
        container.backend.pushConfig()
    }

    fun resetAgents() {
        container.settings.resetAgents()
        container.backend.pushConfig()
    }

    fun fetchModels(baseUrl: String, apiKey: String) {
        _modelFetch.value = ModelFetchState.Loading
        viewModelScope.launch {
            _modelFetch.value = when (val result = modelCatalog.fetch(baseUrl, apiKey)) {
                is ModelListResult.Success -> ModelFetchState.Loaded(result.models)
                is ModelListResult.Failure -> {
                    SaraLog.w("Model fetch failed: ${result.reason} ${result.message}")
                    ModelFetchState.Failed(result.message)
                }
            }
        }
    }

    fun clearModelFetch() {
        _modelFetch.value = null
    }

    // ---------------------------------------------------------------- bubble

    /** Home-screen bubble icon: flips whatever the current state is. */
    fun toggleBubble() = setBubble(!_bubbleVisible.value)

    fun setBubble(enabled: Boolean) {
        if (!enabled) {
            OverlayBubbleService.stop(container.context)
            _bubbleVisible.value = false
            return
        }
        refreshPermissions()
        if (!_permissions.value.overlay) {
            _notice.value = Notice.Text("Allow 'Display over other apps' to use the bubble.")
            return
        }
        OverlayBubbleService.start(container.context)
        _bubbleVisible.value = true
    }

    fun markOnboardingComplete() {
        container.settings.update { it.copy(onboardingComplete = true) }
        refreshPermissions()
    }

    fun consumeNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        // The session is app-scoped on purpose (it must survive rotation), so nothing is
        // stopped here. The foreground service and the notification action own that lifecycle.
        super.onCleared()
    }

    class Factory(private val container: SaraContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SaraViewModel(container) as T
    }
}
