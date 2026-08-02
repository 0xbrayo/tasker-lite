package com.taskerlite.service

import com.taskerlite.bulb.BulbController
import com.taskerlite.bulb.DelayedActionRunner
import com.taskerlite.bulb.SunriseRunner
import com.taskerlite.data.Bulb
import com.taskerlite.data.LightAction
import com.taskerlite.data.LogEntry
import com.taskerlite.data.PreferencesRepository
import com.taskerlite.data.SleepEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RulesEngine(
    private val repo: PreferencesRepository,
) {
    private val mutex = Mutex()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun handle(event: SleepEvent) {
        // Dismiss / stop: tear down dawn + cancel delayed auto-off
        if (event == SleepEvent.ALARM_DISMISSED ||
            event == SleepEvent.SLEEP_TRACKING_STOPPED
        ) {
            SunriseRunner.cancel()
            DelayedActionRunner.cancel()
        }

        val work: List<Pair<Bulb, LightAction>>
        mutex.withLock {
            val state = repo.getState()
            val matching = state.rules.filter { it.enabled && it.event == event.name }

            if (matching.isEmpty()) {
                repo.appendLog(
                    LogEntry(
                        event = event.name,
                        message = "No enabled rule for ${event.displayName}",
                        success = true,
                    )
                )
                return
            }

            work = matching.mapNotNull { rule ->
                val bulb = when {
                    rule.bulbId != null -> state.bulbs.find { it.id == rule.bulbId }
                    else -> state.bulbs.find { it.isDefault } ?: state.bulbs.firstOrNull()
                }
                if (bulb == null) null else bulb to rule.action
            }

            if (work.isEmpty()) {
                repo.appendLog(
                    LogEntry(
                        event = event.name,
                        message = "No bulb configured for rule",
                        success = false,
                    )
                )
                return
            }
        }

        for ((bulb, action) in work) {
            dispatch(event.name, bulb, action)
        }
    }

    suspend fun logUnknown(action: String) {
        repo.appendLog(
            LogEntry(
                event = "UNKNOWN",
                message = "Unhandled intent: $action",
                success = true,
            )
        )
    }

    suspend fun runAction(bulb: Bulb, action: LightAction): Result<Unit> {
        return when {
            action is LightAction.Sunrise -> {
                DelayedActionRunner.cancel(bulb.id)
                repo.appendLog(
                    LogEntry(
                        event = "MANUAL",
                        message = "${bulb.name}: starting ${action.summary()}",
                        success = true,
                    )
                )
                SunriseRunner.start(bulb, action)
                Result.success(Unit)
            }
            action.isLongRunning() -> {
                if (action is LightAction.Power && !action.on) {
                    SunriseRunner.cancel(bulb.id)
                    DelayedActionRunner.cancel(bulb.id)
                }
                repo.appendLog(
                    LogEntry(
                        event = "MANUAL",
                        message = "${bulb.name}: starting ${action.summary()}",
                        success = true,
                    )
                )
                DelayedActionRunner.start(bulb, action)
                Result.success(Unit)
            }
            else -> {
                if (action is LightAction.Power && !action.on) {
                    SunriseRunner.cancel(bulb.id)
                    DelayedActionRunner.cancel(bulb.id)
                }
                BulbController(bulb, repo.context).runAction(action)
            }
        }
    }

    private suspend fun dispatch(eventName: String, bulb: Bulb, action: LightAction) {
        when {
            action is LightAction.Sunrise -> {
                DelayedActionRunner.cancel(bulb.id)
                repo.appendLog(
                    LogEntry(
                        event = eventName,
                        message = "${bulb.name}: starting ${action.summary()}",
                        success = true,
                    )
                )
                SunriseRunner.start(bulb, action) { result ->
                    logScope.launch {
                        repo.appendLog(
                            LogEntry(
                                event = eventName,
                                message = if (result.isSuccess) {
                                    "${bulb.name}: dawn complete / CF started"
                                } else {
                                    "${bulb.name}: dawn failed: " +
                                        (result.exceptionOrNull()?.message ?: "error")
                                },
                                success = result.isSuccess,
                            )
                        )
                    }
                }
            }
            action.isLongRunning() -> {
                // e.g. dim warm → wait 15m → off (must not block the receiver)
                if (action is LightAction.Power && !action.on) {
                    SunriseRunner.cancel(bulb.id)
                }
                repo.appendLog(
                    LogEntry(
                        event = eventName,
                        message = "${bulb.name}: starting ${action.summary()}",
                        success = true,
                    )
                )
                DelayedActionRunner.start(bulb, action) { result ->
                    logScope.launch {
                        repo.appendLog(
                            LogEntry(
                                event = eventName,
                                message = if (result.isSuccess) {
                                    "${bulb.name}: ${action.summary()} complete"
                                } else if (result.exceptionOrNull() is
                                    kotlinx.coroutines.CancellationException
                                ) {
                                    "${bulb.name}: delayed action cancelled"
                                } else {
                                    "${bulb.name}: ${result.exceptionOrNull()?.message ?: "failed"}"
                                },
                                success = result.isSuccess ||
                                    result.exceptionOrNull() is
                                    kotlinx.coroutines.CancellationException,
                            )
                        )
                    }
                }
            }
            else -> {
                if (action is LightAction.Power && !action.on) {
                    SunriseRunner.cancel(bulb.id)
                    DelayedActionRunner.cancel(bulb.id)
                }
                val result = BulbController(bulb, repo.context).runAction(action)
                repo.appendLog(
                    LogEntry(
                        event = eventName,
                        message = if (result.isSuccess) {
                            "${bulb.name} (${bulb.protocolLabel}): ${action.summary()}"
                        } else {
                            "${bulb.name}: ${result.exceptionOrNull()?.message ?: "failed"}"
                        },
                        success = result.isSuccess,
                    )
                )
            }
        }
    }
}
