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
 * One segment of a Yeelight color-flow / phased ramp:
 * `[durationMs, mode=2 (CT), kelvin, brightness]`.
 */
@Serializable
data class DawnPhase(
    val durationMs: Int,
    val kelvin: Int,
    val brightness: Int,
)

/**
 * When a scheduled routine fires relative to solar times or a wall clock.
 */
@Serializable
enum class ScheduleAnchor {
    /** Local civil sunset (GPS/network location). */
    SUNSET,
    /** Local civil sunrise. */
    SUNRISE,
    /** Fixed local wall-clock time. */
    FIXED_TIME,
}

/**
 * Approximate device location used for sunrise/sunset.
 * Coarse GPS (~city) is plenty accurate for solar times.
 */
@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val updatedAtMs: Long = 0L,
    val source: String = "unknown",
) {
    fun isValid(): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}

/**
 * Time-based automation (independent of Sleep as Android events).
 * [offsetMinutes] is added to sunrise/sunset (negative = before).
 * [fixedHour]/[fixedMinute] apply only for [ScheduleAnchor.FIXED_TIME].
 */
@Serializable
data class ScheduledRoutine(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String,
    val anchor: ScheduleAnchor = ScheduleAnchor.SUNSET,
    /** Minutes relative to sunset/sunrise (ignored for FIXED_TIME). */
    val offsetMinutes: Int = 0,
    val fixedHour: Int = 18,
    val fixedMinute: Int = 0,
    val action: LightAction,
    val bulbId: String? = null,
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

    /** Pause between scene steps (e.g. auto-off after N minutes). */
    @Serializable
    data class Wait(
        val durationMs: Long,
    ) : LightAction()

    /**
     * Run [action] only if the bulb reports power = on.
     * If already off (or power cannot be read as on), the action is skipped.
     */
    @Serializable
    data class OnlyIfOn(
        val action: LightAction,
    ) : LightAction()

    /**
     * Multi-phase CT + brightness ramp.
     * Yeelight: single `start_cf` offload; miIO: phone-side phased steps.
     *
     * Morning default (Sleep integration guide):
     * 10 min → 1700K @ 1%, 10 min → 3000K @ 50%, 10 min → 6500K @ 100%.
     */
    @Serializable
    data class Sunrise(
        val phases: List<DawnPhase> = defaultDawnPhases(),
        /** UI label: "Dawn", "Sunset ramp", etc. */
        val label: String = "Dawn",
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
        is Wait -> {
            val ms = durationMs
            when {
                ms >= 60_000 -> "Wait ${ms / 60_000}m"
                ms >= 1_000 -> "Wait ${ms / 1_000}s"
                else -> "Wait ${ms}ms"
            }
        }
        is OnlyIfOn -> "If on: ${action.summary()}"
        is Sunrise -> {
            val ms = totalDurationMs()
            val dur = if (ms >= 60_000) "${ms / 60_000} min" else "${ms / 1000}s"
            val name = label.ifBlank { "Ramp" }
            "$name · ${phases.size} phases · $dur (CF / phased)"
        }
    }

    /** True when the action may run for a long time (must not block the receiver). */
    fun isLongRunning(): Boolean = when (this) {
        is Sunrise -> true
        is Wait -> durationMs > 2_000
        is Scene -> steps.any { it.isLongRunning() }
        is OnlyIfOn -> action.isLongRunning()
        else -> false
    }

    companion object {
        const val FIFTEEN_MIN_MS = 15 * 60 * 1000L

        /** Recommended 30-minute firmware color-flow dawn. */
        fun defaultDawnPhases(): List<DawnPhase> = listOf(
            DawnPhase(durationMs = 600_000, kelvin = 1700, brightness = 1),
            DawnPhase(durationMs = 600_000, kelvin = 3000, brightness = 50),
            DawnPhase(durationMs = 600_000, kelvin = 6500, brightness = 100),
        )

        fun morningSunrise(): Sunrise = Sunrise(phases = defaultDawnPhases(), label = "Dawn")

        /** Short dawn for manual testing (~2 min total). */
        fun morningSunriseTest(): Sunrise = Sunrise(
            phases = listOf(
                DawnPhase(durationMs = 40_000, kelvin = 1700, brightness = 1),
                DawnPhase(durationMs = 40_000, kelvin = 3000, brightness = 50),
                DawnPhase(durationMs = 40_000, kelvin = 6500, brightness = 100),
            ),
            label = "Dawn test",
        )

        /**
         * Evening lights-up: as daylight fades, slowly raise warm ambient brightness.
         * Default 30 min: ultra-dim candle → cozy room → brighter evening.
         */
        fun eveningSunsetRamp(): Sunrise = Sunrise(
            phases = listOf(
                DawnPhase(durationMs = 600_000, kelvin = 2000, brightness = 1),
                DawnPhase(durationMs = 600_000, kelvin = 2400, brightness = 35),
                DawnPhase(durationMs = 600_000, kelvin = 2700, brightness = 70),
            ),
            label = "Sunset ramp",
        )

        /** Short sunset ramp for manual testing (~90 s total). */
        fun eveningSunsetRampTest(): Sunrise = Sunrise(
            phases = listOf(
                DawnPhase(durationMs = 30_000, kelvin = 2000, brightness = 1),
                DawnPhase(durationMs = 30_000, kelvin = 2400, brightness = 35),
                DawnPhase(durationMs = 30_000, kelvin = 2700, brightness = 70),
            ),
            label = "Sunset test",
        )

        /**
         * Sleep tracking: if the bulb is already on, dim warm then power off after [afterMs].
         * If already off, do nothing (do not turn the light on).
         */
        fun dimWarmThenOff(afterMs: Long = FIFTEEN_MIN_MS): OnlyIfOn = OnlyIfOn(
            action = Scene(
                steps = listOf(
                    // Already confirmed on; still set CT/bright without a force power-on first
                    ColorTemp(kelvin = 2700, durationMs = 800),
                    Brightness(percent = 10, durationMs = 800),
                    Wait(durationMs = afterMs),
                    Power(on = false, durationMs = 1000),
                )
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
    val routines: List<ScheduledRoutine> = defaultRoutines(),
    val location: GeoLocation? = null,
    val serviceEnabled: Boolean = false,
    val log: List<LogEntry> = emptyList(),
)

fun defaultRules(): List<Rule> = listOf(
    // Dim warm, then auto-off after 15 minutes
    Rule(
        event = SleepEvent.SLEEP_TRACKING_STARTED.name,
        action = LightAction.dimWarmThenOff(),
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

fun defaultRoutines(): List<ScheduledRoutine> = listOf(
    ScheduledRoutine(
        name = "Sunset lights up",
        anchor = ScheduleAnchor.SUNSET,
        offsetMinutes = 0,
        enabled = true,
        action = LightAction.eveningSunsetRamp(),
    ),
)
