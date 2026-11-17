package com.sara.assistant.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sara.assistant.ui.components.GlassCard
import com.sara.assistant.ui.components.SectionHeader

private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val Grey = Color(0xFF6B7280)

/**
 * First-run health sweep. Every check states its own remedy, because "connection failed"
 * without a next step is what made the original setup painful.
 */
@Composable
fun DiagnosticsScreen(
    checks: List<DiagnosticCheck>?,
    running: Boolean,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (checks == null) onRun()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Everything Sara needs, checked one by one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onRun, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking…")
            } else {
                Text(if (checks == null) "Run checks" else "Re-run checks")
            }
        }

        if (checks != null) {
            val failures = checks.count { it.status == CheckStatus.FAIL }
            val warnings = checks.count { it.status == CheckStatus.WARN }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = when {
                        failures > 0 -> "$failures problem(s), $warnings warning(s)"
                        warnings > 0 -> "Working, with $warnings warning(s)"
                        else -> "All checks passed"
                    },
                    subtitle = "Fix red items first; amber ones are optional.",
                )
            }

            checks.forEach { check -> CheckRow(check) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CheckRow(check: DiagnosticCheck) {
    val color = when (check.status) {
        CheckStatus.OK -> Green
        CheckStatus.WARN -> Amber
        CheckStatus.FAIL -> Red
        CheckStatus.SKIPPED -> Grey
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = check.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                    Text(
                        text = check.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                check.hint?.let { hint ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Fix: $hint",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (check.status == CheckStatus.OK) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
        }
    }
}