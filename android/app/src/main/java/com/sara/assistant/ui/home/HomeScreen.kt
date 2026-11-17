package com.sara.assistant.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sara.assistant.backend.BackendClient
import com.sara.assistant.live.LiveState
import com.sara.assistant.live.SaraUiState
import com.sara.assistant.live.StepStatus
import com.sara.assistant.live.TaskStep
import com.sara.assistant.live.TranscriptLine
import com.sara.assistant.ui.PermissionSnapshot
import com.sara.assistant.ui.components.GlassCard
import com.sara.assistant.ui.components.GradientPill
import com.sara.assistant.ui.components.SectionHeader

@Composable
fun HomeScreen(
    state: SaraUiState,
    permissions: PermissionSnapshot,
    backend: BackendClient.ConnectionState,
    bubbleVisible: Boolean,
    onStart: () -> Unit,
    onStopSession: () -> Unit,
    onStopEverything: () -> Unit,
    onBubbleToggle: () -> Unit,
    onSendText: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    val sessionActive = state.liveState != LiveState.IDLE && state.liveState != LiveState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sara", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = backendLabel(backend),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onBubbleToggle) {
                Icon(
                    Icons.Filled.Chat,
                    contentDescription = "Floating bubble",
                    tint = if (bubbleVisible) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        if (!permissions.allGranted) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "Sara is missing permissions",
                    subtitle = "Microphone, accessibility, overlay and notifications are needed.",
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenOnboarding) { Text("Open setup") }
            }
        }

        SaraOrb(
            state = state.liveState,
            inputLevel = state.inputLevel,
            outputLevel = state.outputLevel,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientPill(stateLabel(state))
        }

        state.statusText.takeIf { it.isNotBlank() }?.let { status ->
            Text(
                text = status,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.lastError?.let { error ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = if (sessionActive) onStopSession else onStart,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (sessionActive) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (sessionActive) "End session" else "Start talking")
            }
            OutlinedButton(onClick = onStopEverything) {
                Icon(Icons.Filled.Bolt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }

        state.activeTaskGoal?.let { goal ->
            TaskTimeline(goal = goal, steps = state.taskSteps)
        }

        TextInputRow(onSend = onSendText)

        Transcript(
            lines = state.transcript,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun TextInputRow(onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        onSend(text)
        draft = ""
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Type to Sara instead…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
        trailingIcon = {
            TextButton(onClick = { submit() }) { Text("Send") }
        },
    )
}

@Composable
private fun TaskTimeline(goal: String, steps: List<TaskStep>) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(title = "Working on it", subtitle = goal)
        Spacer(Modifier.height(10.dp))
        if (steps.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Planning…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            steps.takeLast(6).forEach { step -> StepRow(step) }
        }
    }
}

@Composable
private fun StepRow(step: TaskStep) {
    val color = when (step.status) {
        StepStatus.RUNNING -> MaterialTheme.colorScheme.secondary
        StepStatus.DONE -> Color(0xFF4ADE80)
        StepStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp),
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(color = color) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.agent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = step.detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Transcript(lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            val alignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart
            Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
                GlassCard(Modifier.fillMaxWidth(0.86f)) {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.final) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun stateLabel(state: SaraUiState): String = when (state.liveState) {
    LiveState.IDLE -> "IDLE"
    LiveState.CONNECTING -> "CONNECTING"
    LiveState.LISTENING -> "LISTENING"
    LiveState.THINKING -> "THINKING"
    LiveState.SPEAKING -> "SPEAKING"
    LiveState.ACTING -> "ON YOUR PHONE"
    LiveState.ERROR -> "ERROR"
}

private fun backendLabel(connection: BackendClient.ConnectionState): String = when (connection) {
    BackendClient.ConnectionState.ONLINE -> "backend online"
    BackendClient.ConnectionState.CONNECTING -> "backend connecting…"
    BackendClient.ConnectionState.OFFLINE -> "backend offline"
}
