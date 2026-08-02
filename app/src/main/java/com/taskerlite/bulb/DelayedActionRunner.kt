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
 * Runs long light actions (e.g. dim-warm + wait 15m + off) without blocking
 * the Sleep broadcast receiver. Cancelled when sleep tracking stops.
 *
 * Jobs are tracked per bulb: starting an action on one bulb must not cancel a
 * pending auto-off on another.
 */
object DelayedActionRunner {
    private const val TAG = "DelayedActionRunner"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Cancels pending actions on every bulb (global teardown, e.g. tracking stopped). */
    fun cancel() {
        val ids = jobs.keys.toList()
        ids.forEach { cancel(it) }
        Log.i(TAG, "Delayed actions cancelled on ${ids.size} bulb(s)")
    }

    /** Cancels only the pending action for [bulbId], leaving other bulbs running. */
    fun cancel(bulbId: String) {
        jobs.remove(bulbId)?.cancel()
        Log.i(TAG, "Delayed action cancelled for $bulbId")
    }

    fun start(
        bulb: Bulb,
        action: LightAction,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        jobs.remove(bulb.id)?.cancel()
        val newJob = scope.launch {
            val result = try {
                BulbController(bulb, appContext).runAction(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Result.failure(e)
            }
            if (result.isFailure &&
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException
            ) {
                Log.i(TAG, "Delayed action cancelled mid-run on ${bulb.name}")
                return@launch
            }
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
