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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.taskerlite.astro.SunCalculator
import com.taskerlite.data.GeoLocation
import com.taskerlite.data.LightAction
import com.taskerlite.data.ScheduleAnchor
import com.taskerlite.data.ScheduledRoutine
import com.taskerlite.service.RoutineScheduler
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class RoutinePreset(val label: String) {
    SUNSET_RAMP("Sunset ramp 30 min"),
    SUNSET_RAMP_TEST("Sunset ramp 90s test"),
    DAWN("Dawn 30 min"),
    DIM_WARM("Dim warm (10% · 2700K)"),
    FULL_ON("Full on"),
    OFF("Turn off"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    routines: List<ScheduledRoutine>,
    location: GeoLocation?,
    hasLocationPermission: Boolean,
    statusMessage: String?,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onUpdateAction: (String, LightAction) -> Unit,
    onUpdateOffset: (String, Int) -> Unit,
    onRefreshLocation: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onTestSunsetRamp: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val sunTimes = remember(location, today) {
        location?.takeIf { it.isValid() }?.let {
            SunCalculator.calculate(it.latitude, it.longitude, today, zone)
        }
    }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Routines",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Time-based schedules. Sunset/sunrise use GPS to estimate solar times.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Location & solar times", fontWeight = FontWeight.SemiBold)
                    if (location?.isValid() == true) {
                        Text(
                            "≈ ${"%.2f".format(location.latitude)}, ${"%.2f".format(location.longitude)} " +
                                "(${location.source})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (sunTimes?.sunset != null) {
                            Text(
                                "Today · sunrise ${sunTimes.sunrise?.format(timeFmt) ?: "—"} · " +
                                    "sunset ${sunTimes.sunset.format(timeFmt)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                "No sunrise/sunset today at this latitude (polar day/night).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            "Location needed for sunset/sunrise routines.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hasLocationPermission) {
                            Button(onClick = onRequestLocationPermission) {
                                Text("Grant location")
                            }
                        }
                        OutlinedButton(onClick = onRefreshLocation) {
                            Icon(Icons.Default.MyLocation, contentDescription = null)
                            Spacer(Modifier.padding(4.dp))
                            Text(if (hasLocationPermission) "Refresh GPS" else "Use cached")
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestSunsetRamp, modifier = Modifier.weight(1f)) {
                    Text("Test ramp (90s)")
                }
                OutlinedButton(onClick = onResetDefaults, modifier = Modifier.weight(1f)) {
                    Text("Restore defaults")
                }
            }
        }

        items(routines, key = { it.id }) { routine ->
            RoutineCard(
                routine = routine,
                location = location,
                onToggle = { onToggle(routine.id, it) },
                onDelete = { onDelete(routine.id) },
                onUpdateAction = { onUpdateAction(routine.id, it) },
                onUpdateOffset = { onUpdateOffset(routine.id, it) },
            )
        }

        if (routines.isEmpty()) {
            item {
                Text("No routines. Restore defaults for a sunset lights-up ramp.")
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

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: the default “Sunset lights up” routine starts a 30-minute warm brightness " +
                    "ramp at local sunset (1% → 70%). Adjust the offset to start earlier or later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineCard(
    routine: ScheduledRoutine,
    location: GeoLocation?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdateAction: (LightAction) -> Unit,
    onUpdateOffset: (Int) -> Unit,
) {
    var actionExpanded by remember { mutableStateOf(false) }
    var offsetExpanded by remember { mutableStateOf(false) }
    val anchorSummary = RoutineScheduler.formatAnchorSummary(routine, location)
    val nextRun = RoutineScheduler.formatNextRun(routine, location)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(routine.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        anchorSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        routine.action.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (nextRun != null) {
                        Text(
                            "Next: $nextRun",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(checked = routine.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            if (routine.anchor == ScheduleAnchor.SUNSET || routine.anchor == ScheduleAnchor.SUNRISE) {
                ExposedDropdownMenuBox(
                    expanded = offsetExpanded,
                    onExpandedChange = { offsetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = offsetLabel(routine.offsetMinutes),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Offset from ${routine.anchor.name.lowercase()}") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(offsetExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = offsetExpanded,
                        onDismissRequest = { offsetExpanded = false },
                    ) {
                        OFFSET_OPTIONS.forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text(offsetLabel(minutes)) },
                                onClick = {
                                    onUpdateOffset(minutes)
                                    offsetExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = actionExpanded,
                onExpandedChange = { actionExpanded = it },
            ) {
                OutlinedTextField(
                    value = "Change scene…",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(actionExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = actionExpanded,
                    onDismissRequest = { actionExpanded = false },
                ) {
                    RoutinePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                onUpdateAction(preset.toAction())
                                actionExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private val OFFSET_OPTIONS = listOf(-60, -30, -15, 0, 15, 30, 45, 60)

private fun offsetLabel(minutes: Int): String = when {
    minutes == 0 -> "Exactly at solar time"
    minutes < 0 -> "${-minutes} min before"
    else -> "$minutes min after"
}

private fun RoutinePreset.toAction(): LightAction = when (this) {
    RoutinePreset.SUNSET_RAMP -> LightAction.eveningSunsetRamp()
    RoutinePreset.SUNSET_RAMP_TEST -> LightAction.eveningSunsetRampTest()
    RoutinePreset.DAWN -> LightAction.morningSunrise()
    RoutinePreset.DIM_WARM -> LightAction.Scene(
        listOf(
            LightAction.Power(true, 800),
            LightAction.ColorTemp(2700, 800),
            LightAction.Brightness(10, 800),
        )
    )
    RoutinePreset.FULL_ON -> LightAction.Scene(
        listOf(
            LightAction.Power(true, 400),
            LightAction.Brightness(100, 400),
        )
    )
    RoutinePreset.OFF -> LightAction.Power(on = false, durationMs = 1000)
}
