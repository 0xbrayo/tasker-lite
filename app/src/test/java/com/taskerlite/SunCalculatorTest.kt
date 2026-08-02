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

    /**
     * Regression: solar minutes are an offset from UTC midnight and legitimately fall
     * outside 0…1440 — west of UTC sunset lands past 24:00 UTC, east of UTC sunrise
     * lands before 00:00 UTC. Wrapping into the UTC day shifted results a full local
     * calendar day. Nairobi/London sit in the narrow band where the wrap was invisible.
     */
    @Test
    fun solarTimes_landOnRequestedLocalDate_acrossLongitudes() {
        val date = LocalDate.of(2026, 8, 2)
        val places = listOf(
            Triple("New York", 40.7128 to -74.0060, "America/New_York"),
            Triple("Los Angeles", 34.0522 to -118.2437, "America/Los_Angeles"),
            Triple("Honolulu", 21.3069 to -157.8583, "Pacific/Honolulu"),
            Triple("Auckland", -36.8485 to 174.7633, "Pacific/Auckland"),
            Triple("Sydney", -33.8688 to 151.2093, "Australia/Sydney"),
            Triple("Nairobi", nairobi, "Africa/Nairobi"),
            Triple("London", 51.5074 to -0.1278, "Europe/London"),
        )
        for ((name, coords, zoneId) in places) {
            val z = ZoneId.of(zoneId)
            val sun = SunCalculator.calculate(coords.first, coords.second, date, z)
            val sunrise = sun.sunrise
            val sunset = sun.sunset
            assertNotNull("$name had no sunrise", sunrise)
            assertNotNull("$name had no sunset", sunset)
            assertEquals("$name sunrise on wrong local date", date, sunrise!!.toLocalDate())
            assertEquals("$name sunset on wrong local date", date, sunset!!.toLocalDate())
            assertTrue("$name sunrise $sunrise not before sunset $sunset", sunrise.isBefore(sunset))
        }
    }

    @Test
    fun nextTrigger_sunrise_doesNotSkipTodayInEasternHemisphere() {
        // Sydney is UTC+10, so sunrise falls before 00:00 UTC. The day-shift used to make
        // today's sunrise look like tomorrow's, silently dropping one occurrence.
        val sydney = ZoneId.of("Australia/Sydney")
        val loc = GeoLocation(latitude = -33.8688, longitude = 151.2093)
        val routine = ScheduledRoutine(
            name = "Wake",
            anchor = ScheduleAnchor.SUNRISE,
            offsetMinutes = 0,
            action = LightAction.morningSunrise(),
        )
        // 03:00 local — today's sunrise is still ahead and must be the next trigger
        val now = ZonedDateTime.of(2026, 8, 2, 3, 0, 0, 0, sydney)
        val next = RoutineScheduler.nextTrigger(routine, loc, now, sydney)
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 8, 2), next!!.toLocalDate())
        assertTrue("sunrise hour was ${next.hour}", next.hour in 5..8)
    }

    @Test
    fun nextTrigger_sunset_usesTodayInWesternHemisphere() {
        // Los Angeles is UTC-7, so sunset falls past 24:00 UTC.
        val la = ZoneId.of("America/Los_Angeles")
        val loc = GeoLocation(latitude = 34.0522, longitude = -118.2437)
        val routine = ScheduledRoutine(
            name = "Sunset",
            anchor = ScheduleAnchor.SUNSET,
            offsetMinutes = 0,
            action = LightAction.eveningSunsetRamp(),
        )
        val now = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, la)
        val next = RoutineScheduler.nextTrigger(routine, loc, now, la)
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 8, 2), next!!.toLocalDate())
        assertTrue("sunset hour was ${next.hour}", next.hour in 18..21)
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
        // Log-expanded CF: ends at final lumen/CT targets
        assertTrue(flow.contains("2700,70"))
        assertTrue(flow.split(",").size >= 9 * 4)
        assertEquals(listOf(1, 35, 70), ramp.phases.map { it.brightness })
    }
}
