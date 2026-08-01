package com.taskerlite

import com.taskerlite.data.SleepEvent
import com.taskerlite.sleep.SleepEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepEventsTest {

    @Test
    fun resolve_autoSuffix() {
        assertEquals(
            SleepEvent.SLEEP_TRACKING_STARTED,
            SleepEvents.resolve("com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STARTED_AUTO"),
        )
        assertEquals(
            SleepEvent.ALARM_TRIGGERED,
            SleepEvents.resolve("com.urbandroid.sleep.alarmclock.ALARM_ALERT_START_AUTO"),
        )
        assertEquals(
            SleepEvent.ALARM_DISMISSED,
            SleepEvents.resolve("com.urbandroid.sleep.alarmclock.ALARM_ALERT_DISMISS_AUTO"),
        )
    }

    @Test
    fun resolve_unknown() {
        assertNull(SleepEvents.resolve("com.example.FOO"))
        assertNull(SleepEvents.resolve(null))
        // Pre-_AUTO action names are not handled
        assertNull(SleepEvents.resolve("com.urbandroid.sleep.alarmclock.SLEEP_TRACKING_STARTED"))
    }

    @Test
    fun allActions_useCurrentAutoApi() {
        assertTrue(SleepEvents.ALL_ACTIONS.isNotEmpty())
        assertTrue(SleepEvents.ALL_ACTIONS.all { it.endsWith("_AUTO") })
    }
}
