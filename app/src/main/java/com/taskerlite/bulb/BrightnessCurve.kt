package com.taskerlite.bulb

import com.taskerlite.data.DawnPhase
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Brightness ramps for dawn / sunset.
 *
 * Phase `brightness` values are treated as **percent of max lumens** (1–100).
 * Temporal interpolation of lumen % uses a **log scale** (equal multiplicative
 * steps), which matches how illuminance grows through twilight better than a
 * linear percent ramp. Color temperature is still linearly interpolated.
 *
 * Assumes the bulb API level 1–100 is approximately linear in lumen output.
 */
object BrightnessCurve {

    /**
     * Log-space interpolation of lumen %: geometric mean path from [from] to [to].
     * t = 0 → from, t = 1 → to. Result is always in 1…100.
     */
    fun logLerpLumens(from: Int, to: Int, t: Float): Int {
        val a = from.coerceIn(1, 100).toDouble()
        val b = to.coerceIn(1, 100).toDouble()
        val tt = t.coerceIn(0f, 1f).toDouble()
        if (a == b || tt == 0.0) return a.roundToInt().coerceIn(1, 100)
        if (tt >= 1.0) return b.roundToInt().coerceIn(1, 100)
        val v = exp(ln(a) + (ln(b) - ln(a)) * tt)
        return v.roundToInt().coerceIn(1, 100)
    }

    fun linearLerp(from: Int, to: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return (from + (to - from) * tt).roundToInt()
    }

    /**
     * Expand coarse phases into log-spaced lumen keyframes for Yeelight `start_cf`
     * (firmware only does linear blends per tuple, so we bake the log curve as steps).
     *
     * Default 3 steps/phase → 9 tuples for a 3-phase dawn (common CF size limit).
     */
    fun expandLogPhases(
        phases: List<DawnPhase>,
        stepsPerPhase: Int = 3,
    ): List<DawnPhase> {
        if (phases.isEmpty()) return emptyList()
        val n = max(1, stepsPerPhase)
        // Start from 1% lumens so the first phase ramps up instead of sitting flat
        var prevB = 1
        var prevK = phases.first().kelvin.coerceIn(1700, 6500)
        val out = ArrayList<DawnPhase>(phases.size * n)

        for (phase in phases) {
            val targetB = phase.brightness.coerceIn(1, 100)
            val targetK = phase.kelvin.coerceIn(1700, 6500)
            val baseDur = phase.durationMs / n
            var used = 0
            for (s in 1..n) {
                val t = s.toFloat() / n
                val b = logLerpLumens(prevB, targetB, t)
                val k = linearLerp(prevK, targetK, t).coerceIn(1700, 6500)
                val dur = if (s == n) {
                    (phase.durationMs - used).coerceAtLeast(50)
                } else {
                    baseDur.coerceAtLeast(50).also { used += it }
                }
                // Yeelight durations should be multiples of 50ms
                val dur50 = (dur / 50).coerceAtLeast(1) * 50
                out += DawnPhase(durationMs = dur50, kelvin = k, brightness = b)
            }
            prevB = targetB
            prevK = targetK
        }
        return out
    }

    fun flowExpressionLog(phases: List<DawnPhase>, stepsPerPhase: Int = 3): String =
        expandLogPhases(phases, stepsPerPhase).joinToString(",") {
            "${it.durationMs},2,${it.kelvin},${it.brightness}"
        }
}
