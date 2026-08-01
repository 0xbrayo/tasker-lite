package com.taskerlite.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskerlite.data.AppState

@Composable
fun HomeScreen(
    state: AppState,
    statusMessage: String?,
    onToggleService: (Boolean) -> Unit,
    onQuickOn: () -> Unit,
    onQuickOff: () -> Unit,
    onQuickDim: () -> Unit,
    onQuickSunrise: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val defaultBulb = state.bulbs.find { it.isDefault } ?: state.bulbs.firstOrNull()
    val lastLog = state.log.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tasker Lite",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Sleep as Android → Xiaomi / Yeelight bulb",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automation service", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (state.serviceEnabled) {
                            "Listening for Sleep as Android events"
                        } else {
                            "Off — turn on to react overnight"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.serviceEnabled,
                    onCheckedChange = onToggleService,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default bulb", fontWeight = FontWeight.SemiBold)
                if (defaultBulb == null) {
                    Text(
                        "No bulb yet — open the Bulbs tab to discover or add by IP.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("${defaultBulb.name}  ·  ${defaultBulb.ip}")
                    if (defaultBulb.model != null) {
                        Text(
                            "Model: ${defaultBulb.model}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(onClick = onQuickOn, modifier = Modifier.weight(1f)) {
                            Text("On")
                        }
                        OutlinedButton(onClick = onQuickOff, modifier = Modifier.weight(1f)) {
                            Text("Off")
                        }
                        OutlinedButton(onClick = onQuickDim, modifier = Modifier.weight(1f)) {
                            Text("Dim")
                        }
                    }
                    OutlinedButton(
                        onClick = onQuickSunrise,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Test dawn (2 min)")
                    }
                    OutlinedButton(
                        onClick = onRefreshStatus,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Refresh status")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last event", fontWeight = FontWeight.SemiBold)
                if (lastLog == null) {
                    Text(
                        "Nothing yet. Enable the service and start sleep tracking, or test with adb.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(lastLog.event, fontWeight = FontWeight.Medium)
                    Text(lastLog.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tip: In Sleep as Android go to Settings → Services → Automation and enable it. " +
                "No Tasker install is required — this app listens for the same intents.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
