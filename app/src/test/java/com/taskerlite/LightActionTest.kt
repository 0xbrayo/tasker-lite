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
        assertEquals(
            "600000,2,1700,1,600000,2,3000,50,600000,2,6500,100",
            sunrise.flowExpression(),
        )
    }

    @Test
    fun defaultDismiss_isPowerOff() {
        val dismiss = defaultRules().first { it.event == "ALARM_DISMISSED" }
        val power = dismiss.action as LightAction.Power
        assertEquals(false, power.on)
        assertEquals(1000, power.durationMs)
    }
}
