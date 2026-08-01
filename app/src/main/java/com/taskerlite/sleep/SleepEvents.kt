package com.taskerlite.sleep

import com.taskerlite.data.SleepEvent

/**
 * Sleep as Android intent actions (*_AUTO).
 *
 * Critical per local integration guide:
 * - [ALARM_ALERT_START_AUTO] → dawn simulation
 * - [ALARM_ALERT_DISMISS_AUTO] → stop flow / power off
 *
 * Must be registered at **runtime** (RECEIVER_EXPORTED) from a live service;
 * static manifest filters alone are not reliable for Sleep's implicit broadcasts.
 *
 * @see <a href="https://sleep.urbandroid.org/docs/devs/intent_api.html">Intent API</a>
 */
object SleepEvents {

    private const val PKG = "com.urbandroid.sleep"

    const val ALARM_ALERT_START_AUTO = "$PKG.alarmclock.ALARM_ALERT_START_AUTO"
    const val ALARM_ALERT_DISMISS_AUTO = "$PKG.alarmclock.ALARM_ALERT_DISMISS_AUTO"

    val ACTION_MAP: Map<String, SleepEvent> = mapOf(
        "$PKG.alarmclock.SLEEP_TRACKING_STARTED_AUTO" to SleepEvent.SLEEP_TRACKING_STARTED,
        "$PKG.alarmclock.SLEEP_TRACKING_STOPPED_AUTO" to SleepEvent.SLEEP_TRACKING_STOPPED,
        "$PKG.alarmclock.TIME_TO_BED_ALARM_ALERT_AUTO" to SleepEvent.BEDTIME,
        ALARM_ALERT_START_AUTO to SleepEvent.ALARM_TRIGGERED,
        ALARM_ALERT_DISMISS_AUTO to SleepEvent.ALARM_DISMISSED,
        "$PKG.alarmclock.ALARM_SNOOZE_CLICKED_ACTION_AUTO" to SleepEvent.ALARM_SNOOZED,
        "$PKG.TRACKING_DEEP_SLEEP_AUTO" to SleepEvent.DEEP_SLEEP,
        "$PKG.TRACKING_LIGHT_SLEEP_AUTO" to SleepEvent.LIGHT_SLEEP,
        "$PKG.SMART_PERIOD_AUTO" to SleepEvent.SMART_PERIOD,
        "$PKG.ACTION_LULLABY_START_PLAYBACK_AUTO" to SleepEvent.LULLABY_STARTED,
        "$PKG.ACTION_LULLABY_STOPPED_PLAYBACK_AUTO" to SleepEvent.LULLABY_STOPPED,
    )

    val ALL_ACTIONS: Array<String> = ACTION_MAP.keys.toTypedArray()

    fun resolve(action: String?): SleepEvent? =
        action?.let { ACTION_MAP[it] }
}
