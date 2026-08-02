package com.taskerlite

import com.taskerlite.data.LightAction
import com.taskerlite.data.defaultRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightActionTest {

    @Test
    fun summary_power() {
        assertEquals("Turn on", LightAction.Power(true).summary())
        assertEquals("Turn off", LightAction.Power(false).summary())
    }

    @Test
    fun summary_scene() {
        val scene = LightAction.Scene(
            listOf(
                LightAction.Power(true),
                LightAction.Brightness(10),
            )
        )
        assertTrue(scene.summary().contains("Turn on"))
        assertTrue(scene.summary().contains("Brightness 10%"))
    }

    @Test
    fun defaultRules_coverBedtimeAndAlarm() {
        val events = defaultRules().map { it.event }.toSet()
        assertTrue(events.contains("SLEEP_TRACKING_STARTED"))
        assertTrue(events.contains("ALARM_TRIGGERED"))
        assertTrue(events.contains("ALARM_DISMISSED"))
        assertTrue(events.contains("BEDTIME"))
    }

    @Test
    fun defaultAlarm_is30MinDawnColorFlow() {
        val alarm = defaultRules().first { it.event == "ALARM_TRIGGERED" }
        val sunrise = alarm.action as LightAction.Sunrise
        assertEquals(3, sunrise.phases.size)
        assertEquals(1_800_000, sunrise.totalDurationMs())
        assertEquals("Dawn", sunrise.label)
        // Coarse phases expand to log-lumen CF keyframes (not the old 3 linear tuples)
        val flow = sunrise.flowExpression()
        assertTrue(flow.contains("6500,100") || flow.endsWith("100"))
        assertTrue(flow.split(",").size >= 9 * 4)
        assertTrue(sunrise.summary().contains("log-lumen"))
    }

    @Test
    fun defaultAlarm_phasesStillDeclareLumenTargets() {
        val alarm = defaultRules().first { it.event == "ALARM_TRIGGERED" }
        val sunrise = alarm.action as LightAction.Sunrise
        assertEquals(listOf(1, 50, 100), sunrise.phases.map { it.brightness })
    }

    @Test
    fun sunsetRamp_summary() {
        val summary = LightAction.eveningSunsetRamp().summary()
        assertTrue(summary.contains("Sunset ramp"))
        assertTrue(summary.contains("30 min"))
    }

    @Test
    fun defaultDismiss_isPowerOff() {
        val dismiss = defaultRules().first { it.event == "ALARM_DISMISSED" }
        val power = dismiss.action as LightAction.Power
        assertEquals(false, power.on)
        assertEquals(1000, power.durationMs)
    }

    @Test
    fun sleepStart_dimWarmThenOffAfter15Min_onlyIfOn() {
        val start = defaultRules().first { it.event == "SLEEP_TRACKING_STARTED" }
        val gated = start.action as LightAction.OnlyIfOn
        assertTrue(gated.isLongRunning())
        assertTrue(gated.summary().startsWith("If on:"))
        assertTrue(gated.summary().contains("Wait 15m"))
        val scene = gated.action as LightAction.Scene
        // Must not force power-on (only adjust if already on)
        assertTrue(scene.steps.none { it is LightAction.Power && it.on })
        val wait = scene.steps.filterIsInstance<LightAction.Wait>().single()
        assertEquals(LightAction.FIFTEEN_MIN_MS, wait.durationMs)
        val last = scene.steps.last() as LightAction.Power
        assertEquals(false, last.on)
    }
}
