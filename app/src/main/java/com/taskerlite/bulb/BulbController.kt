package com.taskerlite.bulb

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.taskerlite.data.Bulb
import com.taskerlite.data.DawnPhase
import com.taskerlite.data.LightAction
import com.taskerlite.miio.MiioProtocol
import com.taskerlite.miio.MiotProps
import com.taskerlite.yeelight.YeelightClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Unified controller: miIO/MIoT when token is set, else Yeelight LAN (TCP 55443).
 *
 * Dawn follows sleep_as_android_local_bulb_integration.md:
 * Yeelight → single `start_cf` offload; miIO → phased phone-side ramp + wake lock.
 */
class BulbController(
    private val bulb: Bulb,
    private val appContext: Context? = null,
) {

    private val useMiio: Boolean =
        !bulb.token.isNullOrBlank() && bulb.token.trim().length == 32

    private var miioClient: MiioProtocol? = null

    suspend fun setPower(on: Boolean, durationMs: Int = 500): Result<Unit> = runCatching {
        if (useMiio) {
            miio().send("set_properties", MiotProps.setBool(SIID_LIGHT, PIID_POWER, on))
        } else {
            val client = YeelightClient(bulb.ip, bulb.port)
            if (!on) {
                // Integration guide: stop color flow before power-off teardown
                client.stopColorFlow()
                delay(50)
            }
            client.setPower(on, durationMs).getOrThrow()
        }
    }

    suspend fun setBright(percent: Int, durationMs: Int = 500): Result<Unit> = runCatching {
        val p = percent.coerceIn(1, 100)
        if (useMiio) {
            miio().send("set_properties", MiotProps.setMultiple(
                MiotProps.propBool(SIID_LIGHT, PIID_POWER, true),
                MiotProps.propInt(SIID_LIGHT, PIID_BRIGHT, p)
            ))
        } else {
            YeelightClient(bulb.ip, bulb.port).setBright(p, durationMs).getOrThrow()
        }
    }

    suspend fun setBrightOnly(percent: Int): Result<Unit> = runCatching {
        val p = percent.coerceIn(1, 100)
        if (useMiio) {
            miio().send("set_properties", MiotProps.setInt(SIID_LIGHT, PIID_BRIGHT, p))
        } else {
            YeelightClient(bulb.ip, bulb.port).setBright(p, 300).getOrThrow()
        }
    }

    suspend fun setCt(kelvin: Int, durationMs: Int = 500): Result<Unit> = runCatching {
        val k = kelvin.coerceIn(1700, 6500)
        if (useMiio) {
            miio().send("set_properties", MiotProps.setMultiple(
                MiotProps.propBool(SIID_LIGHT, PIID_POWER, true),
                MiotProps.propInt(SIID_LIGHT, PIID_CT, k)
            ))
        } else {
            YeelightClient(bulb.ip, bulb.port).setCt(k, durationMs).getOrThrow()
        }
    }

    suspend fun setCtOnly(kelvin: Int): Result<Unit> = runCatching {
        val k = kelvin.coerceIn(1700, 6500)
        if (useMiio) {
            miio().send("set_properties", MiotProps.setInt(SIID_LIGHT, PIID_CT, k))
        } else {
            YeelightClient(bulb.ip, bulb.port).setCt(k, 300).getOrThrow()
        }
    }

    suspend fun setRgb(color: Int, durationMs: Int = 500): Result<Unit> = runCatching {
        val rgb = color and 0xFFFFFF
        if (useMiio) {
            miio().send("set_properties", MiotProps.setMultiple(
                MiotProps.propBool(SIID_LIGHT, PIID_POWER, true),
                MiotProps.propInt(SIID_LIGHT, PIID_RGB, rgb)
            ))
        } else {
            YeelightClient(bulb.ip, bulb.port).setRgb(rgb, durationMs).getOrThrow()
        }
    }

    suspend fun stopDawn(): Result<Unit> = runCatching {
        if (useMiio) {
            // Phone-side ramp cancelled by SunriseRunner; nothing extra on device
            Unit
        } else {
            YeelightClient(bulb.ip, bulb.port).stopColorFlow().getOrThrow()
        }
    }

    suspend fun ping(): Result<Map<String, String>> = runCatching {
        if (useMiio) {
            val power = readProp(PIID_POWER)
            val bright = readProp(PIID_BRIGHT)
            val ct = readProp(PIID_CT)
            mapOf(
                "protocol" to "miio",
                "power" to if (power == true || power == "true") "on" else power.toString(),
                "bright" to bright.toString(),
                "ct" to ct.toString(),
                "model" to (bulb.model ?: "xiaomi.light.bulb"),
            )
        } else {
            YeelightClient(bulb.ip, bulb.port).ping().getOrThrow() + ("protocol" to "yeelight")
        }
    }

    suspend fun runAction(action: LightAction): Result<Unit> {
        return when (action) {
            is LightAction.Power -> setPower(action.on, action.durationMs)
            is LightAction.Brightness -> setBright(action.percent, action.durationMs)
            is LightAction.ColorTemp -> setCt(action.kelvin, action.durationMs)
            is LightAction.Rgb -> setRgb(action.color, action.durationMs)
            is LightAction.Scene -> {
                var last: Result<Unit> = Result.success(Unit)
                for (step in action.steps) {
                    last = runAction(step)
                    if (last.isFailure) {
                        Log.w(TAG, "Scene step failed: ${step.summary()}")
                        return last
                    }
                    delay(80)
                }
                last
            }
            is LightAction.Sunrise -> runSunrise(action)
        }
    }

    /**
     * Yeelight: one `start_cf` (firmware offload, survives Doze).
     * miIO: phased ramp matching the same CT/brightness targets + partial wake lock.
     */
    suspend fun runSunrise(action: LightAction.Sunrise): Result<Unit> {
        val phases = action.phases.ifEmpty { LightAction.defaultDawnPhases() }
        return if (useMiio) {
            runMiioPhasedDawn(phases)
        } else {
            runYeelightColorFlow(action.flowExpression())
        }
    }

    private suspend fun runYeelightColorFlow(flow: String): Result<Unit> = runCatching {
        val client = YeelightClient(bulb.ip, bulb.port)
        // Ensure on, then offload 30-min flow to bulb (count=1, action=0 stay at end)
        client.setPower(true, 300).getOrThrow()
        delay(80)
        client.startColorFlow(count = 1, action = 0, flowExpression = flow).getOrThrow()
        Log.i(TAG, "Yeelight start_cf on ${bulb.name}: $flow")
        Unit
    }

    private suspend fun runMiioPhasedDawn(phases: List<DawnPhase>): Result<Unit> = runCatching {
        val wakeLock = acquireWakeLock(phases.sumOf { it.durationMs }.toLong() + 60_000L)
        try {
            Log.i(TAG, "miIO phased dawn on ${bulb.name}: ${phases.size} phases")
            setPower(true).getOrThrow()
            delay(100)

            var prevBright = 1
            var prevKelvin = phases.first().kelvin

            // Seed first target immediately (ultra-dim warm)
            setCtOnly(phases.first().kelvin).getOrThrow()
            setBrightOnly(phases.first().brightness.coerceAtLeast(1)).getOrThrow()
            prevBright = phases.first().brightness.coerceAtLeast(1)
            prevKelvin = phases.first().kelvin

            for ((index, phase) in phases.withIndex()) {
                currentCoroutineContext().ensureActive()
                val targetB = phase.brightness.coerceIn(1, 100)
                val targetK = phase.kelvin.coerceIn(1700, 6500)

                // Within each phase, step toward targets (firmware CF does this smoothly)
                val stepCount = max(1, phase.durationMs / STEP_MS)
                val stepDelay = phase.durationMs.toLong() / stepCount

                for (s in 1..stepCount) {
                    currentCoroutineContext().ensureActive()
                    delay(stepDelay)
                    currentCoroutineContext().ensureActive()
                    val t = s.toFloat() / stepCount
                    val b = lerp(prevBright, targetB, t).coerceIn(1, 100)
                    val k = lerp(prevKelvin, targetK, t).coerceIn(1700, 6500)
                    setBrightOnly(b).getOrThrow()
                    if (s % 2 == 0 || s == stepCount) {
                        setCtOnly(k).getOrThrow()
                    }
                }
                prevBright = targetB
                prevKelvin = targetK
                Log.d(TAG, "Phase ${index + 1}/${phases.size} done → $targetB% / ${targetK}K")
            }
            Log.i(TAG, "miIO dawn complete on ${bulb.name}")
            Unit
        } finally {
            releaseWakeLock(wakeLock)
        }
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        throw e
    }

    private fun acquireWakeLock(timeoutMs: Long): PowerManager.WakeLock? {
        val ctx = appContext ?: return null
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "taskerlite:dawn").apply {
                setReferenceCounted(false)
                acquire(timeoutMs.coerceAtMost(35 * 60 * 1000L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock unavailable", e)
            null
        }
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Exception) {
        }
    }

    private fun miio(): MiioProtocol {
        miioClient?.let { return it }
        return MiioProtocol(
            host = bulb.ip,
            tokenHex = bulb.token!!.trim(),
            port = bulb.port,
        ).also { miioClient = it }
    }

    private suspend fun readProp(piid: Int): Any? {
        val result = miio().send("get_properties", MiotProps.get(SIID_LIGHT, piid))
        val arr = result as? JsonArray ?: result.jsonArray
        val first = arr.firstOrNull()?.jsonObject ?: return null
        val value = first["value"] ?: return null
        val prim = value as? JsonPrimitive ?: return value.toString()
        return prim.booleanOrNull
            ?: prim.intOrNull
            ?: prim.contentOrNull
            ?: prim.content
    }

    companion object {
        private const val TAG = "BulbController"
        private const val SIID_LIGHT = 2
        private const val PIID_POWER = 1
        private const val PIID_BRIGHT = 2
        private const val PIID_CT = 3
        private const val PIID_RGB = 4
        /** miIO step interval within a dawn phase (~15s). */
        private const val STEP_MS = 15_000

        private fun lerp(a: Int, b: Int, t: Float): Int =
            (a + (b - a) * t).roundToInt()
    }
}
