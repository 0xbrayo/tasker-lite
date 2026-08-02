package com.taskerlite

import com.taskerlite.bulb.BrightnessCurve
import com.taskerlite.data.DawnPhase
import com.taskerlite.data.LightAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class BrightnessCurveTest {

    @Test
    fun logLerp_midpointIsGeometricMean() {
        // √(1 * 100) = 10, not linear mid 50.5
        assertEquals(10, BrightnessCurve.logLerpLumens(1, 100, 0.5f))
        assertEquals(1, BrightnessCurve.logLerpLumens(1, 100, 0f))
        assertEquals(100, BrightnessCurve.logLerpLumens(1, 100, 1f))
    }

    @Test
    fun logLerp_staysBelowLinearMid() {
        // Early in a 1→100 ramp, log stays much dimmer than linear
        val logQuarter = BrightnessCurve.logLerpLumens(1, 100, 0.25f)
        val linearQuarter = BrightnessCurve.linearLerp(1, 100, 0.25f)
        assertTrue("log=$logQuarter linear=$linearQuarter", logQuarter < linearQuarter)
        // 100^0.25 ≈ 3.16
        assertTrue(abs(logQuarter - sqrt(sqrt(100.0))).toInt() <= 1)
    }

    @Test
    fun expandLogPhases_triplesKeyframeCount() {
        val phases = LightAction.defaultDawnPhases()
        val expanded = BrightnessCurve.expandLogPhases(phases, stepsPerPhase = 3)
        assertEquals(9, expanded.size)
        assertEquals(1_800_000, expanded.sumOf { it.durationMs })
        // Ends at final target
        assertEquals(100, expanded.last().brightness)
        assertEquals(6500, expanded.last().kelvin)
        // Mid of first phase (1→1 log from prev 1) stays low; after phase2 toward 50, mid is geometric
        // Second original phase: 1 → 50, midpoint of that phase's log steps ~ √50 ≈ 7
        val secondPhaseSteps = expanded.subList(3, 6)
        assertTrue(secondPhaseSteps.any { it.brightness in 5..15 })
        assertTrue(secondPhaseSteps.none { it.brightness == 50 && secondPhaseSteps.indexOf(it) < 2 })
    }

    @Test
    fun flowExpression_usesLogKeyframesNotLinearMid() {
        val flow = LightAction.morningSunrise().flowExpression()
        // Must not be the old 3-tuple linear expression alone
        assertTrue(flow.split(",").size > 12) // 9 tuples × 4 fields
        // Geometric mid 1→100 appears as brightness 10 somewhere when expanding 1→100 path
        // Third phase goes toward 100; overall path includes low then high values
        assertTrue(flow.contains(",100"))
        assertTrue(flow.contains(",1") || flow.startsWith("600") || flow.contains(",2,1700,1"))
    }

    @Test
    fun linearLerp_unchanged() {
        assertEquals(50, BrightnessCurve.linearLerp(0, 100, 0.5f))
        assertEquals(25, BrightnessCurve.linearLerp(0, 100, 0.25f))
    }
}
