package com.taskerlite.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.taskerlite.MainActivity
import com.taskerlite.astro.SunCalculator
import com.taskerlite.data.GeoLocation
import com.taskerlite.data.PreferencesRepository
import com.taskerlite.data.ScheduleAnchor
import com.taskerlite.data.ScheduledRoutine
import com.taskerlite.location.LocationHelper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Schedules [ScheduledRoutine]s via [AlarmManager] (alarm-clock style for Doze resilience).
 * Sunset/sunrise anchors use GPS/network location when available.
 */
object RoutineScheduler {
    private const val TAG = "RoutineScheduler"
    const val EXTRA_ROUTINE_ID = "routine_id"
    const val ACTION_FIRE = "com.taskerlite.action.ROUTINE_FIRE"

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun rescheduleAll(context: Context, repo: PreferencesRepository) {
        val appCtx = context.applicationContext
        val state = repo.getState()
        val location = LocationHelper.resolve(appCtx, state.location)
        if (location != null && location != state.location) {
            repo.setLocation(location)
        }

        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)

        // Cancel alarms for every known routine id, then re-arm enabled ones
        for (routine in state.routines) {
            cancelAlarm(appCtx, am, routine.id)
        }

        for (routine in state.routines.filter { it.enabled }) {
            val next = nextTrigger(routine, location, now, zone)
            if (next == null) {
                Log.w(TAG, "Cannot schedule ${routine.name}: need location for ${routine.anchor}")
                continue
            }
            scheduleAlarm(appCtx, am, routine.id, next.toInstant().toEpochMilli())
            Log.i(
                TAG,
                "Scheduled ${routine.name} at ${next.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} " +
                    "(${routine.anchor} ${routine.offsetMinutes}m)",
            )
        }
    }

    fun cancelAll(context: Context, routineIds: Collection<String>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (id in routineIds) {
            cancelAlarm(context, am, id)
        }
    }

    /**
     * Next fire time at or after [now] (if within 15s, rolls to the following occurrence).
     */
    fun nextTrigger(
        routine: ScheduledRoutine,
        location: GeoLocation?,
        now: ZonedDateTime = ZonedDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ZonedDateTime? {
        // Look today and tomorrow (polar / past-today edge cases)
        for (dayOffset in 0..2) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val candidate = triggerOnDate(routine, location, date, zone) ?: continue
            // Skip if already passed (or about to fire within 5s — avoid double-fire races)
            if (candidate.isAfter(now.plusSeconds(5))) {
                return candidate
            }
        }
        return null
    }

    fun triggerOnDate(
        routine: ScheduledRoutine,
        location: GeoLocation?,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ZonedDateTime? {
        return when (routine.anchor) {
            ScheduleAnchor.FIXED_TIME -> {
                val t = LocalTime.of(
                    routine.fixedHour.coerceIn(0, 23),
                    routine.fixedMinute.coerceIn(0, 59),
                )
                ZonedDateTime.of(date, t, zone)
            }
            ScheduleAnchor.SUNSET, ScheduleAnchor.SUNRISE -> {
                val loc = location?.takeIf { it.isValid() } ?: return null
                val sun = SunCalculator.calculate(loc.latitude, loc.longitude, date, zone)
                val base = when (routine.anchor) {
                    ScheduleAnchor.SUNSET -> sun.sunset
                    ScheduleAnchor.SUNRISE -> sun.sunrise
                    else -> null
                } ?: return null
                base.plusMinutes(routine.offsetMinutes.toLong())
            }
        }
    }

    fun formatAnchorSummary(
        routine: ScheduledRoutine,
        location: GeoLocation?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val today = LocalDate.now(zone)
        return when (routine.anchor) {
            ScheduleAnchor.FIXED_TIME -> {
                val t = LocalTime.of(
                    routine.fixedHour.coerceIn(0, 23),
                    routine.fixedMinute.coerceIn(0, 59),
                )
                "Every day at ${t.format(timeFmt)}"
            }
            ScheduleAnchor.SUNSET -> {
                val offset = formatOffset(routine.offsetMinutes)
                val sun = location?.takeIf { it.isValid() }?.let {
                    SunCalculator.calculate(it.latitude, it.longitude, today, zone).sunset
                }
                if (sun != null) {
                    val fire = sun.plusMinutes(routine.offsetMinutes.toLong())
                    "At sunset$offset · today ${fire.format(timeFmt)}"
                } else {
                    "At sunset$offset · set location for time"
                }
            }
            ScheduleAnchor.SUNRISE -> {
                val offset = formatOffset(routine.offsetMinutes)
                val sun = location?.takeIf { it.isValid() }?.let {
                    SunCalculator.calculate(it.latitude, it.longitude, today, zone).sunrise
                }
                if (sun != null) {
                    val fire = sun.plusMinutes(routine.offsetMinutes.toLong())
                    "At sunrise$offset · today ${fire.format(timeFmt)}"
                } else {
                    "At sunrise$offset · set location for time"
                }
            }
        }
    }

    fun formatNextRun(
        routine: ScheduledRoutine,
        location: GeoLocation?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (!routine.enabled) return null
        val next = nextTrigger(routine, location, ZonedDateTime.now(zone), zone) ?: return null
        return next.format(DateTimeFormatter.ofPattern("EEE HH:mm"))
    }

    private fun formatOffset(minutes: Int): String = when {
        minutes == 0 -> ""
        minutes > 0 -> " +${minutes}m"
        else -> " ${minutes}m"
    }

    private fun scheduleAlarm(
        context: Context,
        am: AlarmManager,
        routineId: String,
        triggerAtMs: Long,
    ) {
        val pi = pendingIntent(context, routineId)
        val show = PendingIntent.getActivity(
            context,
            routineId.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, show), pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    private fun cancelAlarm(context: Context, am: AlarmManager, routineId: String) {
        am.cancel(pendingIntent(context, routineId))
    }

    private fun pendingIntent(context: Context, routineId: String): PendingIntent {
        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ROUTINE_ID, routineId)
            // Unique data so PendingIntents don't collapse across routines
            data = android.net.Uri.parse("taskerlite://routine/$routineId")
        }
        return PendingIntent.getBroadcast(
            context,
            routineId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Instant helper for tests. */
    fun nextTriggerEpochMs(
        routine: ScheduledRoutine,
        location: GeoLocation?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        return nextTrigger(routine, location, now, zone)?.toInstant()?.toEpochMilli()
    }
}
