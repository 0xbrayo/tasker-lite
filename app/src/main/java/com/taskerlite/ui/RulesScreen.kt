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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskerlite.data.LightAction
import com.taskerlite.data.Rule

private enum class PresetAction(val label: String) {
    OFF("Turn off (stop dawn)"),
    DIM_WARM("Dim warm (10% · 2700K)"),
    DAWN("Dawn 30 min (CF / phased)"),
    DAWN_FAST("Dawn 2 min test"),
    DAYLIGHT("Daylight (100% · 5000K)"),
    FULL_ON("Full on (100%)"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    rules: List<Rule>,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onUpdateAction: (String, LightAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Rules",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Map Sleep as Android events to light scenes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onResetDefaults) {
                Text("Restore default rules")
            }
        }

        items(rules, key = { it.id }) { rule ->
            RuleCard(
                rule = rule,
                onToggle = { onToggle(rule.id, it) },
                onDelete = { onDelete(rule.id) },
                onUpdateAction = { onUpdateAction(rule.id, it) },
            )
        }

        if (rules.isEmpty()) {
            item {
                Text("No rules. Restore defaults to get started.")
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleCard(
    rule: Rule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdateAction: (LightAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val eventLabel = rule.sleepEvent()?.displayName ?: rule.event

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(eventLabel, fontWeight = FontWeight.SemiBold)
                    Text(
                        rule.action.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = "Change scene…",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    PresetAction.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                onUpdateAction(preset.toAction())
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun PresetAction.toAction(): LightAction = when (this) {
    PresetAction.OFF -> LightAction.Power(on = false, durationMs = 1000)
    PresetAction.DIM_WARM -> LightAction.Scene(
        listOf(
            LightAction.Power(true, 800),
            LightAction.ColorTemp(2700, 800),
            LightAction.Brightness(10, 800),
        )
    )
    PresetAction.DAWN -> LightAction.morningSunrise()
    PresetAction.DAWN_FAST -> LightAction.morningSunriseTest()
    PresetAction.DAYLIGHT -> LightAction.Scene(
        listOf(
            LightAction.Power(true, 500),
            LightAction.ColorTemp(5000, 500),
            LightAction.Brightness(100, 500),
        )
    )
    PresetAction.FULL_ON -> LightAction.Scene(
        listOf(
            LightAction.Power(true, 400),
            LightAction.Brightness(100, 400),
        )
    )
}

