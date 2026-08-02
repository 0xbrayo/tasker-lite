package com.taskerlite.bulb

import android.content.Context
import android.util.Log
import com.taskerlite.data.Bulb
import com.taskerlite.data.LightAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs dawn simulations without blocking the broadcast receiver.
 * Yeelight path finishes after one `start_cf`; miIO path may run ~30 minutes.
 *
 * Jobs are tracked per bulb so a ramp on one bulb does not cancel a ramp on another.
 */
object SunriseRunner {
    private const val TAG = "SunriseRunner"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val activeBulbs = ConcurrentHashMap<String, Bulb>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Cancels dawn on every bulb (global teardown, e.g. alarm dismissed). */
    fun cancel() {
        val ids = (jobs.keys + activeBulbs.keys).toSet()
        ids.forEach { cancel(it) }
        Log.i(TAG, "Dawn cancelled on ${ids.size} bulb(s)")
    }

    /** Cancels dawn for [bulbId] only, and tells that bulb to stop its color flow. */
    fun cancel(bulbId: String) {
        val bulb = activeBulbs.remove(bulbId)
        jobs.remove(bulbId)?.cancel()
        if (bulb != null) {
            scope.launch {
                runCatching {
                    BulbController(bulb, appContext).stopDawn()
                }
            }
        }
        Log.i(TAG, "Dawn cancelled for $bulbId")
    }

    fun start(
        bulb: Bulb,
        action: LightAction.Sunrise,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        jobs.remove(bulb.id)?.cancel()
        activeBulbs[bulb.id] = bulb
        val newJob = scope.launch {
            val result = try {
                BulbController(bulb, appContext).runSunrise(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Result.failure(e)
            }
            if (result.isFailure &&
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException
            ) {
                Log.i(TAG, "Dawn cancelled mid-run on ${bulb.name}")
                activeBulbs.remove(bulb.id, bulb)
                return@launch
            }
            activeBulbs.remove(bulb.id, bulb)
            onFinished(result)
        }
        jobs[bulb.id] = newJob
        newJob.invokeOnCompletion {
            jobs.remove(bulb.id, newJob)
        }
    }

    val isRunning: Boolean
        get() = jobs.values.any { it.isActive }
}
