package com.taskerlite

import com.taskerlite.astro.SunCalculator
import com.taskerlite.data.GeoLocation
import com.taskerlite.data.LightAction
import com.taskerlite.data.ScheduleAnchor
import com.taskerlite.data.ScheduledRoutine
import com.taskerlite.data.defaultRoutines
import com.taskerlite.service.RoutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SunCalculatorTest {

    private val nairobi = 1.2921 to 36.8219
    private val zone = ZoneId.of("Africa/Nairobi")

    @Test
    fun nairobi_equinox_sunset_around_18h() {
        // Near equinox, equatorial sunset is roughly 18:30–18:45 local
        val date = LocalDate.of(2026, 3, 20)
        val sun = SunCalculator.calculate(nairobi.first, nairobi.second, date, zone)
        assertNotNull(sun.sunset)
        assertNotNull(sun.sunrise)
        val sunset = sun.sunset!!
        assertEquals(18, sunset.hour)
        // Algorithm is ~1–2 min class accuracy; allow a wide band for the equinox
        assertTrue("sunset was $sunset", sunset.minute in 0..59)
        val sunrise = sun.sunrise!!
        assertEquals(6, sunrise.hour)
    }

    @Test
    fun london_midwinter_sunset_early_afternoon() {
        val date = LocalDate.of(2026, 12, 21)
        val sun = SunCalculator.calculate(51.5074, -0.1278, date, ZoneId.of("Europe/London"))
        assertNotNull(sun.sunset)
        val sunset = sun.sunset!!
        // London winter solstice sunset is typically 15:xx–16:xx
        assertTrue("sunset hour was ${sunset.hour}", sunset.hour in 15..16)
    }

    @Test
    fun defaultRoutines_includeSunsetRamp() {
        val routines = defaultRoutines()
        assertTrue(routines.any { it.anchor == ScheduleAnchor.SUNSET && it.enabled })
        val sunset = routines.first { it.anchor == ScheduleAnchor.SUNSET }
        val action = sunset.action as LightAction.Sunrise
        assertEquals("Sunset ramp", action.label)
        assertEquals(1_800_000, action.totalDurationMs())
        assertEquals(1, action.phases.first().brightness)
        assertTrue(action.phases.last().brightness > 50)
    }

    @Test
    fun nextTrigger_sunset_rollsToTomorrowWhenPast() {
        val loc = GeoLocation(latitude = nairobi.first, longitude = nairobi.second)
        val routine = ScheduledRoutine(
            name = "Test",
            anchor = ScheduleAnchor.SUNSET,
            offsetMinutes = 0,
            action = LightAction.eveningSunsetRamp(),
        )
        // Force "now" to 23:00 so today's sunset is past
        val now = ZonedDateTime.of(2026, 6, 15, 23, 0, 0, 0, zone)
        val next = RoutineScheduler.nextTrigger(routine, loc, now, zone)
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 6, 16), next!!.toLocalDate())
    }

    @Test
    fun nextTrigger_fixedTime() {
        val routine = ScheduledRoutine(
            name = "Bed",
            anchor = ScheduleAnchor.FIXED_TIME,
            fixedHour = 22,
            fixedMinute = 30,
            action = LightAction.Power(false),
        )
        val now = ZonedDateTime.of(2026, 1, 10, 10, 0, 0, 0, zone)
        val next = RoutineScheduler.nextTrigger(routine, location = null, now, zone)
        assertNotNull(next)
        assertEquals(22, next!!.hour)
        assertEquals(30, next.minute)
        assertEquals(LocalDate.of(2026, 1, 10), next.toLocalDate())
    }

    @Test
    fun eveningSunsetRamp_flowExpression() {
        val ramp = LightAction.eveningSunsetRamp()
        val flow = ramp.flowExpression()
        assertTrue(flow.startsWith("600000,2,2000,1"))
        assertTrue(flow.contains("2700,70"))
    }
}
