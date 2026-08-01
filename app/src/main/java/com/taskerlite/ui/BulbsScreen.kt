package com.taskerlite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.taskerlite.data.Bulb

@Composable
fun BulbsScreen(
    bulbs: List<Bulb>,
    discovering: Boolean,
    statusMessage: String?,
    onDiscover: () -> Unit,
    onAddManual: (name: String, ip: String, token: String) -> Unit,
    onSetDefault: (String) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Bulbs",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Xiaomi MIoT bulbs need IP + 32-char token. Yeelight LAN bulbs can use Discover.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onDiscover, enabled = !discovering) {
                    Text(if (discovering) "Scanning…" else "Discover Yeelight")
                }
                if (discovering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(24.dp)
                            .padding(4.dp)
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Add Xiaomi / Yeelight bulb", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("IP address") },
                        placeholder = { Text("192.168.8.19") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("miIO token (32 hex chars)") },
                        placeholder = { Text("required for Xiaomi MIoT") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Token is stored only on this phone. Leave empty for Yeelight LAN only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            onAddManual(name, ip, token)
                            name = ""
                            ip = ""
                            token = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add bulb")
                    }
                }
            }
        }

        if (statusMessage != null) {
            item {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        items(bulbs, key = { it.id }) { bulb ->
            BulbCard(
                bulb = bulb,
                onSetDefault = { onSetDefault(bulb.id) },
                onDelete = { onDelete(bulb.id) },
                onTest = { onTest(bulb.id) },
                onToggle = { on -> onToggle(bulb.id, on) },
            )
        }

        if (bulbs.isEmpty()) {
            item {
                Text(
                    "No bulbs saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BulbCard(
    bulb: Bulb,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bulb.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${bulb.ip} · ${bulb.protocolLabel}" +
                            (bulb.model?.let { " · $it" } ?: "") +
                            if (bulb.usesMiio) " · token ✓" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bulb.isDefault) {
                        Text(
                            "Default",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onSetDefault) {
                    Icon(
                        imageVector = if (bulb.isDefault) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "Set default",
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest) { Text("Test") }
                OutlinedButton(onClick = { onToggle(true) }) { Text("On") }
                OutlinedButton(onClick = { onToggle(false) }) { Text("Off") }
            }
        }
    }
}
