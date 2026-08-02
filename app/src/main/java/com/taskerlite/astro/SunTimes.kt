package com.taskerlite.astro

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Civil sunrise / sunset for a given date and geographic coordinates.
 *
 * Uses a compact NOAA-style solar position algorithm (accurate to ~1–2 minutes
 * for mid-latitudes — plenty for scheduling ambient lighting).
 */
data class SunTimes(
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val date: LocalDate,
    val zoneId: ZoneId,
) {
    val hasSunrise: Boolean get() = sunrise != null
    val hasSunset: Boolean get() = sunset != null
}

object SunCalculator {

    /**
     * @param latitude  degrees, −90…90
     * @param longitude degrees, −180…180 (east positive)
     * @param date      civil date in [zone]
     * @param zone      local time zone for the result instants
     */
    fun calculate(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SunTimes {
        val lat = latitude.coerceIn(-89.9, 89.9)
        val lon = longitude.coerceIn(-180.0, 180.0)

        // Julian day at local noon approximation (UTC-based JD for the calendar day)
        val jd = julianDay(date.year, date.monthValue, date.dayOfMonth)
        val jc = (jd - 2451545.0) / 36525.0

        val geomMeanLong = (280.46646 + jc * (36000.76983 + 0.0003032 * jc)) % 360.0
        val geomMeanAnom = 357.52911 + jc * (35999.05029 - 0.0001537 * jc)
        val eccent = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)

        val sunEqCtr = sin(rad(geomMeanAnom)) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
            sin(rad(2 * geomMeanAnom)) * (0.019993 - 0.000101 * jc) +
            sin(rad(3 * geomMeanAnom)) * 0.000289

        val sunTrueLong = geomMeanLong + sunEqCtr
        val sunAppLong = sunTrueLong - 0.00569 - 0.00478 * sin(rad(125.04 - 1934.136 * jc))
        val meanObliq = 23.0 + (26.0 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60.0) / 60.0
        val obliqCorr = meanObliq + 0.00256 * cos(rad(125.04 - 1934.136 * jc))

        val decl = deg(asin(sin(rad(obliqCorr)) * sin(rad(sunAppLong))))

        val y = tan(rad(obliqCorr / 2.0)).let { it * it }
        val eqTime = 4.0 * deg(
            y * sin(2 * rad(geomMeanLong)) -
                2 * eccent * sin(rad(geomMeanAnom)) +
                4 * eccent * y * sin(rad(geomMeanAnom)) * cos(2 * rad(geomMeanLong)) -
                0.5 * y * y * sin(4 * rad(geomMeanLong)) -
                1.25 * eccent * eccent * sin(2 * rad(geomMeanAnom)),
        )

        // Civil twilight horizon: −0.833° accounts for refraction + solar disk
        val cosHa = (sin(rad(-0.833)) - sin(rad(lat)) * sin(rad(decl))) /
            (cos(rad(lat)) * cos(rad(decl)))

        if (cosHa > 1.0) {
            // Sun never rises (polar night)
            return SunTimes(sunrise = null, sunset = null, date = date, zoneId = zone)
        }
        if (cosHa < -1.0) {
            // Sun never sets (midnight sun) — treat as no discrete events
            return SunTimes(sunrise = null, sunset = null, date = date, zoneId = zone)
        }

        val ha = deg(acos(cosHa.coerceIn(-1.0, 1.0))) // degrees
        val solarNoonUtcMin = 720.0 - 4.0 * lon - eqTime // minutes from UTC midnight
        val sunriseUtcMin = solarNoonUtcMin - 4.0 * ha
        val sunsetUtcMin = solarNoonUtcMin + 4.0 * ha

        val utcDateStart = date.atStartOfDay(ZoneId.of("UTC")).toInstant()
        return SunTimes(
            sunrise = minutesFromUtcMidnight(utcDateStart, sunriseUtcMin, zone),
            sunset = minutesFromUtcMidnight(utcDateStart, sunsetUtcMin, zone),
            date = date,
            zoneId = zone,
        )
    }

    private fun minutesFromUtcMidnight(
        utcMidnight: Instant,
        minutes: Double,
        zone: ZoneId,
    ): ZonedDateTime {
        // [minutes] is an offset from UTC midnight and may legitimately fall outside
        // 0…1440: west of UTC, sunset lands past 24:00 UTC; east of UTC, sunrise lands
        // before 00:00 UTC. Wrapping into the UTC day would shift the result a full
        // local calendar day, so let the instant carry across the boundary.
        val ms = (minutes * 60_000.0).toLong()
        return utcMidnight.plusMillis(ms).atZone(zone)
    }

    /** Julian Day Number at 0h UT for the given Gregorian calendar date. */
    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun rad(deg: Double): Double = deg * PI / 180.0
    private fun deg(rad: Double): Double = rad * 180.0 / PI
}
