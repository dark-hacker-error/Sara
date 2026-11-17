package com.sara.assistant.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.sara.assistant.ui.PermissionSnapshot
import com.sara.assistant.ui.components.GlassCard
import com.sara.assistant.ui.components.SectionHeader

/**
 * Guided permission walk. Accessibility and overlay cannot be granted with a dialog, so each
 * row deep-links to the exact system screen the toggle lives on.
 */
@Composable
fun OnboardingScreen(
    permissions: PermissionSnapshot,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Meet Sara", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A real-time voice assistant that can actually drive your phone. " +
                "Grant these four things and she works.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionRow(
            title = "Microphone",
            detail = "So Sara can hear you, continuously, in real time.",
            granted = permissions.microphone,
            actionLabel = "Allow",
            onAction = onRequestMicrophone,
        )
        PermissionRow(
            title = "Accessibility service",
            detail = "Reads the screen and performs taps, swipes and typing. Required for phone control.",
            granted = permissions.accessibility,
            actionLabel = "Open settings",
            onAction = onOpenAccessibility,
        )
        PermissionRow(
            title = "Display over other apps",
            detail = "Powers the floating bubble so you can summon Sara from anywhere.",
            granted = permissions.overlay,
            actionLabel = "Open settings",
            onAction = onOpenOverlay,
        )
        PermissionRow(
            title = "Notifications",
            detail = "Keeps the voice session alive in the background without being killed.",
            granted = permissions.notifications,
            actionLabel = "Allow",
            onAction = onRequestNotifications,
        )

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Gemini API key",
                subtitle = "Live API key from aistudio.google.com. Stored encrypted on this device only.",
            )
            Spacer(Modifier.height(10.dp))
            ApiKeyField(apiKey = apiKey, onApiKeyChange = onApiKeyChange)
        }

        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
            enabled = permissions.microphone,
        ) {
            Text(if (permissions.allGranted) "Start using Sara" else "Continue anyway")
        }

        if (!permissions.microphone) {
            Text(
                text = "Microphone access is required — Sara cannot run without it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ApiKeyField(apiKey: String, onApiKeyChange: (String) -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("API key") },
        placeholder = { Text("AIza…") },
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
    )
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (granted) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!granted) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            } else {
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF4ADE80),
                )
            }
        }
    }
}
