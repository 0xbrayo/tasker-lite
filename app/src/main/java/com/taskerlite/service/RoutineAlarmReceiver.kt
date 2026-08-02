package com.taskerlite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskerlite.TaskerLiteApp
import com.taskerlite.bulb.SunriseRunner
import com.taskerlite.data.LightAction
import com.taskerlite.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a [com.taskerlite.data.ScheduledRoutine] alarm elapses.
 * Runs the light action, logs, then re-arms the next occurrence.
 */
class RoutineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val routineId = intent?.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_ID) ?: return
        val app = context.applicationContext as? TaskerLiteApp ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                runRoutine(app, routineId)
            } catch (e: Exception) {
                Log.e(TAG, "Routine $routineId failed", e)
            } finally {
                // Always re-arm schedules so a failure does not strand the chain
                runCatching { RoutineScheduler.rescheduleAll(app, app.repository) }
                pending.finish()
            }
        }
    }

    private suspend fun runRoutine(app: TaskerLiteApp, routineId: String) {
        val repo = app.repository
        val state = repo.getState()
        val routine = state.routines.find { it.id == routineId }
        if (routine == null) {
            Log.w(TAG, "Unknown routine id $routineId")
            return
        }
        if (!routine.enabled) {
            Log.i(TAG, "Routine ${routine.name} disabled — skip")
            return
        }

        val bulb = when {
            routine.bulbId != null -> state.bulbs.find { it.id == routine.bulbId }
            else -> state.bulbs.find { it.isDefault } ?: state.bulbs.firstOrNull()
        }
        if (bulb == null) {
            repo.appendLog(
                LogEntry(
                    event = "ROUTINE",
                    message = "${routine.name}: no bulb configured",
                    success = false,
                )
            )
            return
        }

        repo.appendLog(
            LogEntry(
                event = "ROUTINE",
                message = "${routine.name} → ${bulb.name}: ${routine.action.summary()}",
                success = true,
            )
        )
        Log.i(TAG, "Running routine ${routine.name} on ${bulb.name}")

        when (val action = routine.action) {
            is LightAction.Sunrise -> {
                SunriseRunner.start(bulb, action) { result ->
                    CoroutineScope(Dispatchers.IO).launch {
                        repo.appendLog(
                            LogEntry(
                                event = "ROUTINE",
                                message = if (result.isSuccess) {
                                    "${routine.name}: ramp complete / CF started"
                                } else {
                                    "${routine.name}: ${result.exceptionOrNull()?.message ?: "failed"}"
                                },
                                success = result.isSuccess,
                            )
                        )
                    }
                }
            }
            else -> {
                if (action is LightAction.Power && !action.on) {
                    SunriseRunner.cancel(bulb.id)
                }
                val result = com.taskerlite.bulb.BulbController(bulb, app).runAction(action)
                repo.appendLog(
                    LogEntry(
                        event = "ROUTINE",
                        message = if (result.isSuccess) {
                            "${routine.name}: ${action.summary()}"
                        } else {
                            "${routine.name}: ${result.exceptionOrNull()?.message ?: "failed"}"
                        },
                        success = result.isSuccess,
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "RoutineAlarm"
    }
}
