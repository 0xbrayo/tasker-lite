package com.taskerlite.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Bulb(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    /** Yeelight LAN port (55443) or miIO UDP port (54321). */
    val port: Int = 54321,
    val model: String? = null,
    val yeelightId: String? = null,
    /** 32-char hex miIO token. When set, control uses MIoT over UDP 54321. */
    val token: String? = null,
    val isDefault: Boolean = false,
) {
    val usesMiio: Boolean
        get() = !token.isNullOrBlank() && token.trim().length == 32

    val protocolLabel: String
        get() = if (usesMiio) "miIO" else "Yeelight LAN"
}

enum class SleepEvent(val displayName: String) {
    SLEEP_TRACKING_STARTED("Sleep tracking started"),
    SLEEP_TRACKING_STOPPED("Sleep tracking stopped"),
    BEDTIME("Bedtime"),
    ALARM_TRIGGERED("Alarm triggered"),
    ALARM_DISMISSED("Alarm dismissed"),
    ALARM_SNOOZED("Alarm snoozed"),
    DEEP_SLEEP("Deep sleep"),
    LIGHT_SLEEP("Light sleep"),
    SMART_PERIOD("Smart period"),
    LULLABY_STARTED("Lullaby started"),
    LULLABY_STOPPED("Lullaby stopped"),
}

/**
 * One segment of a Yeelight color-flow / dawn simulation:
 * `[durationMs, mode=2 (CT), kelvin, brightness]`.
 */
@Serializable
data class DawnPhase(
    val durationMs: Int,
    val kelvin: Int,
    val brightness: Int,
)

@Serializable
sealed class LightAction {
    @Serializable
    data class Power(
        val on: Boolean,
        val durationMs: Int = 500,
    ) : LightAction()

    @Serializable
    data class Brightness(
        val percent: Int,
        val durationMs: Int = 500,
    ) : LightAction()

    @Serializable
    data class ColorTemp(
        val kelvin: Int,
        val durationMs: Int = 500,
    ) : LightAction()

    @Serializable
    data class Rgb(
        val color: Int,
        val durationMs: Int = 500,
    ) : LightAction()

    @Serializable
    data class Scene(
        val steps: List<LightAction>,
    ) : LightAction()

    /**
     * 30-minute dawn simulation matching Sleep integration guide:
     * offload to Yeelight `start_cf` when LAN is available; otherwise phased miIO ramp.
     *
     * Default phases (from sleep_as_android_local_bulb_integration.md):
     * 10 min → 1700K @ 1%, 10 min → 3000K @ 50%, 10 min → 6500K @ 100%.
     */
    @Serializable
    data class Sunrise(
        val phases: List<DawnPhase> = defaultDawnPhases(),
    ) : LightAction() {
        /** Yeelight `start_cf` flow expression (mode 2 = color temperature). */
        fun flowExpression(): String =
            phases.joinToString(",") { "${it.durationMs},2,${it.kelvin},${it.brightness}" }

        fun totalDurationMs(): Int = phases.sumOf { it.durationMs }
    }

    fun summary(): String = when (this) {
        is Power -> if (on) "Turn on" else "Turn off"
        is Brightness -> "Brightness $percent%"
        is ColorTemp -> "Color temp ${kelvin}K"
        is Rgb -> "RGB #${color.toString(16).padStart(6, '0')}"
        is Scene -> steps.joinToString(" → ") { it.summary() }
        is Sunrise -> {
            val ms = totalDurationMs()
            val label = if (ms >= 60_000) "${ms / 60_000} min" else "${ms / 1000}s"
            "Dawn ${phases.size} phases · $label (CF / phased)"
        }
    }

    companion object {
        /** Recommended 30-minute firmware color-flow dawn. */
        fun defaultDawnPhases(): List<DawnPhase> = listOf(
            DawnPhase(durationMs = 600_000, kelvin = 1700, brightness = 1),
            DawnPhase(durationMs = 600_000, kelvin = 3000, brightness = 50),
            DawnPhase(durationMs = 600_000, kelvin = 6500, brightness = 100),
        )

        fun morningSunrise(): Sunrise = Sunrise(phases = defaultDawnPhases())

        /** Short dawn for manual testing (~2 min total). */
        fun morningSunriseTest(): Sunrise = Sunrise(
            phases = listOf(
                DawnPhase(durationMs = 40_000, kelvin = 1700, brightness = 1),
                DawnPhase(durationMs = 40_000, kelvin = 3000, brightness = 50),
                DawnPhase(durationMs = 40_000, kelvin = 6500, brightness = 100),
            )
        )
    }
}

@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val event: String,
    val action: LightAction,
    val bulbId: String? = null,
) {
    fun sleepEvent(): SleepEvent? =
        SleepEvent.entries.find { it.name == event }
}

@Serializable
data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val event: String,
    val message: String,
    val success: Boolean,
)

@Serializable
data class AppState(
    val bulbs: List<Bulb> = emptyList(),
    val rules: List<Rule> = defaultRules(),
    val serviceEnabled: Boolean = false,
    val log: List<LogEntry> = emptyList(),
)

fun defaultRules(): List<Rule> = listOf(
    Rule(
        event = SleepEvent.SLEEP_TRACKING_STARTED.name,
        action = LightAction.Scene(
            steps = listOf(
                LightAction.Power(on = true, durationMs = 800),
                LightAction.ColorTemp(kelvin = 2700, durationMs = 800),
                LightAction.Brightness(percent = 10, durationMs = 800),
            )
        ),
    ),
    Rule(
        event = SleepEvent.BEDTIME.name,
        action = LightAction.Scene(
            steps = listOf(
                LightAction.Power(on = true, durationMs = 800),
                LightAction.ColorTemp(kelvin = 2700, durationMs = 800),
                LightAction.Brightness(percent = 10, durationMs = 800),
            )
        ),
    ),
    // ALARM_ALERT_START_AUTO → 30-min dawn (start_cf / phased)
    Rule(
        event = SleepEvent.ALARM_TRIGGERED.name,
        action = LightAction.morningSunrise(),
    ),
    // ALARM_ALERT_DISMISS_AUTO → stop flow + power off (integration guide)
    Rule(
        event = SleepEvent.ALARM_DISMISSED.name,
        action = LightAction.Power(on = false, durationMs = 1000),
    ),
)
