package com.sara.assistant.agent

import com.sara.assistant.backend.BackendClient
import com.sara.assistant.backend.BackendEvent
import com.sara.assistant.backend.newTaskId
import com.sara.assistant.core.SaraLog
import com.sara.assistant.live.FunctionCall
import com.sara.assistant.live.LiveToolExecutor
import com.sara.assistant.phone.PhoneController
import com.sara.assistant.safety.SafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Events the UI cares about, emitted while tasks run. */
sealed interface RouterEvent {
    class TaskStarted(val goal: String) : RouterEvent
    class Step(val agent: String, val detail: String, val status: String) : RouterEvent
    data object TaskFinished : RouterEvent
    class TaskFailed(val message: String) : RouterEvent
    class ConfirmationNeeded(val reason: String) : RouterEvent
    class Notice(val message: String) : RouterEvent
}

/**
 * The bridge between Sara's voice session and the agent team on the backend.
 *
 * Live only ever calls two tools: `delegate_task` and `stop_everything`. This class turns a
 * delegation into a request/response round trip over [BackendClient], and while that runs it
 * serves the backend's `device_action` messages by driving the accessibility service.
 *
 * That split is the whole point of the design: the backend plans, the phone executes, and
 * every step is reported back so the UI can show what Sara is doing and why.
 */
class DelegationRouter(
    private val backend: BackendClient,
    private val phone: PhoneController,
    private val safety: SafetyGuard,
    private val scope: CoroutineScope,
) : LiveToolExecutor {

    private val _events = MutableSharedFlow<RouterEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RouterEvent> = _events.asSharedFlow()

    /** Task currently being delegated, if any. */
    private val activeTask = AtomicReference<String?>(null)

    init {
        // Single consumer for backend events: no races between the delegate call and the
        // device-action pump, and no lost frames.
        scope.launch {
            backend.events.collect { event ->
                when (event) {
                    is BackendEvent.Step -> _events.tryEmit(
                        RouterEvent.Step(event.agent, event.detail, event.status),
                    )

                    is BackendEvent.DeviceAction -> handleDeviceAction(event)

                    is BackendEvent.Ready -> _events.tryEmit(
                        RouterEvent.Notice("Backend ready — agents: ${event.agents.joinToString()}"),
                    )

                    is BackendEvent.Error -> _events.tryEmit(RouterEvent.TaskFailed(event.message))

                    is BackendEvent.Disconnected -> _events.tryEmit(
                        RouterEvent.Notice("Backend offline: ${event.reason}"),
                    )
                }
            }
        }
    }

    override suspend fun execute(call: FunctionCall): JSONObject = when (call.name) {
        "delegate_task" -> delegate(call)
        "stop_everything" -> stopEverything()
        else -> {
            SaraLog.w("Unknown tool call: ${call.name}")
            JSONObject().put("ok", false).put("error", "Unknown tool '${call.name}'")
        }
    }

    // ---------------------------------------------------------------- delegate_task

    private suspend fun delegate(call: FunctionCall): JSONObject {
        val goal = call.stringArg("goal")
        val kind = call.stringArg("kind").ifBlank { "auto" }

        if (goal.isBlank()) {
            return JSONObject().put("ok", false).put("error", "delegate_task needs a goal")
        }
        if (safety.isStopped) {
            return JSONObject().put("ok", false).put("error", "Stopped by the user")
        }

        val taskId = newTaskId()
        activeTask.set(taskId)
        _events.tryEmit(RouterEvent.TaskStarted(goal))

        val result = runCatching {
            backend.delegate(taskId, goal, kind)
        }.getOrElse { Result.failure(it) }

        activeTask.compareAndSet(taskId, null)

        return result.fold(
            onSuccess = { text ->
                _events.tryEmit(RouterEvent.TaskFinished)
                JSONObject().apply {
                    put("ok", true)
                    put("result", text.ifBlank { "Done." })
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Task failed"
                _events.tryEmit(RouterEvent.TaskFailed(message))
                JSONObject().apply {
                    put("ok", false)
                    put("error", message)
                }
            },
        )
    }

    // ---------------------------------------------------------------- stop_everything

    private suspend fun stopEverything(): JSONObject {
        safety.requestStop()
        activeTask.get()?.let { taskId -> backend.cancel(taskId) }
        activeTask.set(null)
        // The stop flag is cleared on the next user turn; see SaraViewModel.
        _events.tryEmit(RouterEvent.Notice("Stopped"))
        return JSONObject().put("ok", true).put("result", "Stopped everything.")
    }

    /** Clears the hard-stop latch so the next delegation can run. */
    fun resumeAfterStop() = safety.clearStop()

    /** Non-suspending stop, for the UI's Stop button. */
    fun requestStop() {
        scope.launch { stopEverything() }
    }

    // ---------------------------------------------------------------- device actions

    /**
     * Serves one `device_action` from the backend. Sensitive actions are refused the first
     * time so the agent has to come back with the user's spoken confirmation.
     */
    private suspend fun handleDeviceAction(event: BackendEvent.DeviceAction) {
        val taskId = event.taskId
        val callId = event.callId

        if (safety.isStopped) {
            reply(taskId, callId, ok = false, error = "Stopped by the user")
            return
        }

        val risk = safety.classify(event.action, event.params)
        if (risk == SafetyGuard.Risk.CONFIRM) {
            val signature = safety.signature(event.action, event.params)
            if (!safety.isApproved(signature)) {
                safety.updatePending(signature)
                _events.tryEmit(
                    RouterEvent.ConfirmationNeeded(
                        "This looks sensitive (${event.action} on " +
                            "'${event.params.optString("text").ifBlank { event.params.optString("query") }}'). " +
                            "Ask the user to confirm out loud before continuing.",
                    ),
                )
                reply(
                    taskId = taskId,
                    callId = callId,
                    ok = false,
                    error = "NEEDS_CONFIRMATION: this action is sensitive. Ask the user to confirm " +
                        "out loud, then call delegate_task again with the same goal.",
                    extra = JSONObject().put("needs_confirmation", true),
                )
                return
            }
            safety.consumeApproval(signature)
        }

        val outcome = runCatching { phone.execute(event.action, event.params) }
            .getOrElse { error ->
                SaraLog.e("device action failed", error)
                JSONObject().put("ok", false).put("error", error.message ?: "Action failed")
            }

        // After anything that changes the screen, hand back a fresh tree so the agent can
        // verify the step instead of assuming it worked.
        val verified = outcome.optBoolean("ok", false) && event.action !in NO_VERIFY_ACTIONS
        if (verified) {
            val service = com.sara.assistant.phone.SaraAccessibilityService.instance
            if (service != null) {
                runCatching { service.readScreen() }.getOrNull()?.let { snapshot ->
                    outcome.put("screen", snapshot)
                }
                outcome.put("current_package", service.currentPackage())
            }
        }

        reply(taskId, callId, outcome.optBoolean("ok", false), outcome.optString("error"), outcome)
    }

    private fun reply(
        taskId: String,
        callId: String,
        ok: Boolean,
        error: String? = null,
        extra: JSONObject? = null,
    ) {
        backend.deviceResult(
            taskId = taskId,
            callId = callId,
            ok = ok,
            data = extra ?: JSONObject(),
            error = error,
        )
    }

    private companion object {
        /** Actions that don't change the visible screen, so a re-read is wasted work. */
        val NO_VERIFY_ACTIONS = setOf("read_screen", "list_apps")
    }
}
