package com.sara.assistant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sara.assistant.backend.BackendClient
import com.sara.assistant.backend.HealthResult
import com.sara.assistant.data.AgentConfig
import com.sara.assistant.data.AgentRole
import com.sara.assistant.data.BackendEndpoint
import com.sara.assistant.data.LiveCatalog
import com.sara.assistant.data.LiveSettings
import com.sara.assistant.data.ProviderKind
import com.sara.assistant.data.SaraSettings
import com.sara.assistant.ui.ModelFetchState
import com.sara.assistant.ui.PermissionSnapshot
import com.sara.assistant.ui.components.GlassCard
import com.sara.assistant.ui.components.SectionHeader
import kotlin.math.roundToInt

private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)

@Composable
fun SettingsScreen(
    settings: SaraSettings,
    permissions: PermissionSnapshot,
    backend: BackendClient.ConnectionState,
    backendError: String?,
    backendTest: HealthResult?,
    testingBackend: Boolean,
    liveModels: List<String>?,
    modelFetch: ModelFetchState?,
    onBack: () -> Unit,
    onUpdateLive: ((LiveSettings) -> LiveSettings) -> Unit,
    onUpdateBackendUrl: (String) -> Unit,
    onTestBackend: (String) -> Unit,
    onClearBackendTest: () -> Unit,
    onFetchLiveModels: () -> Unit,
    onClearLiveModels: () -> Unit,
    onUpsertAgent: (AgentConfig) -> Unit,
    onRemoveAgent: (String) -> Unit,
    onResetAgents: () -> Unit,
    onFetchModels: (String, String) -> Unit,
    onClearModelFetch: () -> Unit,
    onBubbleToggle: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var editing by remember { mutableStateOf<AgentConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        BackendSection(
            settings = settings,
            backend = backend,
            backendError = backendError,
            backendTest = backendTest,
            testingBackend = testingBackend,
            onUpdateBackendUrl = onUpdateBackendUrl,
            onTestBackend = onTestBackend,
            onClearBackendTest = onClearBackendTest,
            onOpenDiagnostics = onOpenDiagnostics,
        )

        GeminiSection(
            settings = settings,
            liveModels = liveModels,
            onUpdateLive = onUpdateLive,
            onFetchLiveModels = onFetchLiveModels,
            onClearLiveModels = onClearLiveModels,
        )

        SubAgentSection(
            agents = settings.agents,
            onAdd = {
                editing = AgentConfig(
                    name = "agent_${settings.agents.size + 1}",
                    role = AgentRole.CUSTOM,
                )
            },
            onEdit = { editing = it },
            onRemove = onRemoveAgent,
            onReset = onResetAgents,
        )

        PermissionsSection(
            permissions = permissions,
            onOpenAccessibility = onOpenAccessibility,
            onOpenOverlay = onOpenOverlay,
            onOpenNotificationSettings = onOpenNotificationSettings,
        )

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionHeader(
                        title = "Floating bubble",
                        subtitle = "Summon Sara over any app.",
                    )
                }
                Switch(checked = permissions.overlay, onCheckedChange = onBubbleToggle)
            }
        }

        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Open diagnostics")
        }

        Spacer(Modifier.height(24.dp))
    }

    editing?.let { agent ->
        AgentEditorDialog(
            initial = agent,
            modelFetch = modelFetch,
            onFetchModels = onFetchModels,
            onClearFetch = onClearModelFetch,
            onDismiss = {
                editing = null
                onClearModelFetch()
            },
            onSave = {
                onUpsertAgent(it)
                editing = null
                onClearModelFetch()
            },
        )
    }
}

// ---------------------------------------------------------------- backend

@Composable
private fun BackendSection(
    settings: SaraSettings,
    backend: BackendClient.ConnectionState,
    backendError: String?,
    backendTest: HealthResult?,
    testingBackend: Boolean,
    onUpdateBackendUrl: (String) -> Unit,
    onTestBackend: (String) -> Unit,
    onClearBackendTest: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var draft by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    val endpoint = remember(draft) { BackendEndpoint.parse(draft) }
    val parsed = endpoint.getOrNull()

    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Backend",
            subtitle = "Status: ${when (backend) {
                BackendClient.ConnectionState.ONLINE -> "connected"
                BackendClient.ConnectionState.CONNECTING -> "connecting"
                BackendClient.ConnectionState.OFFLINE -> "offline"
            }}",
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onClearBackendTest()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL (http)") },
            placeholder = { Text("http://127.0.0.1:8000") },
            singleLine = true,
            isError = parsed == null,
            supportingText = {
                Text(
                    text = parsed?.let { "WebSocket: ${it.wsUrl}" }
                        ?: (endpoint.exceptionOrNull()?.message ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onUpdateBackendUrl(draft) },
                enabled = parsed != null && draft != settings.backendUrl,
            ) {
                Text("Save & reconnect")
            }
            OutlinedButton(
                onClick = { onTestBackend(parsed?.httpBase ?: draft) },
                enabled = !testingBackend,
            ) {
                if (testingBackend) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Test connection")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "USB: `adb reverse tcp:8000 tcp:8000` then http://127.0.0.1:8000\n" +
                "Same Wi-Fi: http://<PC-LAN-IP>:8000\n" +
                "Emulator: http://10.0.2.2:8000\n" +
                "Tunnel: https://<your-tunnel-host>",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (backendTest) {
            is HealthResult.Healthy -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "OK — v${backendTest.version}, ADK ${if (backendTest.adkReady) "ready" else "not ready"}, " +
                        "Gemini key ${if (backendTest.geminiKeyPresent) "present" else "missing"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (backendTest.adkReady) Green else Amber,
                )
            }

            is HealthResult.Failure -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    backendTest.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Red,
                )
                Text(
                    backendTest.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            null -> Unit
        }

        backendError?.let { error ->
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.labelSmall, color = Amber)
            TextButton(onClick = onOpenDiagnostics) { Text("Diagnose") }
        }
    }
}

// ---------------------------------------------------------------- Gemini

@Composable
private fun GeminiSection(
    settings: SaraSettings,
    liveModels: List<String>?,
    onUpdateLive: ((LiveSettings) -> LiveSettings) -> Unit,
    onFetchLiveModels: () -> Unit,
    onClearLiveModels: () -> Unit,
) {
    val live = settings.live
    val modelOptions = remember(liveModels) {
        (LiveCatalog.knownModels + liveModels.orEmpty()).distinct()
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Gemini Live",
            subtitle = "Your voice brain. Keys stay encrypted on device.",
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = live.geminiApiKey,
            onValueChange = { value -> onUpdateLive { it.copy(geminiApiKey = value.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFetchLiveModels) {
                Text("Fetch Live models from Google")
            }
            if (liveModels != null) {
                TextButton(onClick = onClearLiveModels) { Text("Clear") }
            }
        }
        liveModels?.let { models ->
            Text(
                text = if (models.isEmpty()) {
                    "No Live models listed for this key."
                } else {
                    "${models.size} Live models: ${models.take(4).joinToString()}…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "Live model",
            value = live.model,
            options = modelOptions,
            onSelect = { value -> onUpdateLive { it.copy(model = value) } },
        )

        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "Fallback model",
            value = live.fallbackModel,
            options = modelOptions,
            onSelect = { value -> onUpdateLive { it.copy(fallbackModel = value) } },
        )

        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "Voice",
            value = live.voice,
            options = LiveCatalog.voices,
            onSelect = { value -> onUpdateLive { it.copy(voice = value) } },
        )

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Barge-in",
            subtitle = "Speaking over Sara cuts her off instantly.",
            checked = live.bargeIn,
            onChange = { value -> onUpdateLive { it.copy(bargeIn = value) } },
        )
        SwitchRow(
            title = "Input transcript",
            subtitle = "Show what Sara heard.",
            checked = live.inputTranscription,
            onChange = { value -> onUpdateLive { it.copy(inputTranscription = value) } },
        )
        SwitchRow(
            title = "Output transcript",
            subtitle = "Show what Sara said.",
            checked = live.outputTranscription,
            onChange = { value -> onUpdateLive { it.copy(outputTranscription = value) } },
        )

        Spacer(Modifier.height(12.dp))
        SliderRow(
            title = "Silence before replying",
            value = live.silenceDurationMs.toFloat(),
            range = 200f..2000f,
            steps = 17,
            suffix = "ms",
            onChange = { value -> onUpdateLive { it.copy(silenceDurationMs = value.roundToInt()) } },
        )
        Text(
            "Docs suggest 500–800ms. Lower feels snappier; much lower chops sentences apart.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        SliderRow(
            title = "Speech start padding",
            value = live.prefixPaddingMs.toFloat(),
            range = 0f..500f,
            steps = 9,
            suffix = "ms",
            onChange = { value -> onUpdateLive { it.copy(prefixPaddingMs = value.roundToInt()) } },
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = live.systemPrompt,
            onValueChange = { value -> onUpdateLive { it.copy(systemPrompt = value) } },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp),
            label = { Text("Sara's instructions") },
        )
    }
}

// ---------------------------------------------------------------- subagents

@Composable
private fun SubAgentSection(
    agents: List<AgentConfig>,
    onAdd: () -> Unit,
    onEdit: (AgentConfig) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Subagents",
            subtitle = "Each one gets its own base URL, key and model. Any OpenAI-compatible API works.",
        )
        Spacer(Modifier.height(10.dp))

        agents.forEach { agent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(agent.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${agent.role.name} · ${agent.model.ifBlank { "no model set" }}" +
                            if (agent.isRunnable) "" else " · incomplete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onEdit(agent) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${agent.name}")
                }
                IconButton(onClick = { onRemove(agent.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${agent.name}")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAdd) { Text("Add subagent") }
            OutlinedButton(onClick = onReset) { Text("Reset defaults") }
        }
    }
}

@Composable
private fun AgentEditorDialog(
    initial: AgentConfig,
    modelFetch: ModelFetchState?,
    onFetchModels: (String, String) -> Unit,
    onClearFetch: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (AgentConfig) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var revealed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subagent") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.trim()) },
                    label = { Text("Agent name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                DropdownField(
                    label = "Role",
                    value = draft.role.name,
                    options = AgentRole.entries.map { it.name },
                    onSelect = { draft = draft.copy(role = AgentRole.fromKey(it)) },
                )

                DropdownField(
                    label = "Provider",
                    value = draft.provider.name,
                    options = ProviderKind.entries.map { it.name },
                    onSelect = { draft = draft.copy(provider = ProviderKind.fromKey(it)) },
                )

                SwitchRow(
                    title = "Enabled",
                    subtitle = "Disabled agents are skipped by the planner.",
                    checked = draft.enabled,
                    onChange = { draft = draft.copy(enabled = it) },
                )

                if (draft.provider == ProviderKind.OPENAI_COMPATIBLE) {
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it.trim()) },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://openrouter.ai/api/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it.trim()) },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = if (revealed) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { revealed = !revealed }) {
                                Text(if (revealed) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (draft.provider == ProviderKind.OPENAI_COMPATIBLE) {
                    OutlinedButton(
                        onClick = { onFetchModels(draft.baseUrl, draft.apiKey) },
                        enabled = draft.baseUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Fetch available models")
                    }
                }

                when (modelFetch) {
                    is ModelFetchState.Loading -> Text(
                        "Loading models…",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is ModelFetchState.Failed -> Text(
                        modelFetch.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is ModelFetchState.Loaded -> Column {
                        Text(
                            "${modelFetch.models.size} models found — tap one to select",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            modelFetch.models.forEach { model ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            draft = draft.copy(model = model.id)
                                            onClearFetch()
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = model.id,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (model.looksFree) {
                                        Text(
                                            text = "likely free",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    null -> Unit
                }

                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { draft = draft.copy(systemPrompt = it) },
                    label = { Text("Extra instructions (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = draft.name.isNotBlank() && draft.model.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---------------------------------------------------------------- permissions

@Composable
private fun PermissionsSection(
    permissions: PermissionSnapshot,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(title = "Permissions")
        Spacer(Modifier.height(10.dp))
        PermissionStatusRow("Microphone", permissions.microphone, null)
        PermissionStatusRow("Accessibility", permissions.accessibility, onOpenAccessibility)
        PermissionStatusRow("Display over apps", permissions.overlay, onOpenOverlay)
        PermissionStatusRow("Notifications", permissions.notifications, onOpenNotificationSettings)
    }
}

@Composable
private fun PermissionStatusRow(title: String, granted: Boolean, onOpen: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (granted) "Granted" else "Missing",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) Green else MaterialTheme.colorScheme.error,
        )
        if (!granted && onOpen != null) {
            TextButton(onClick = onOpen) { Text("Fix") }
        }
    }
}

// ---------------------------------------------------------------- shared widgets

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            trailingIcon = {
                TextButton(onClick = { expanded = true }) { Text("Change") }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "$title: ${value.roundToInt()}$suffix",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}