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
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs long light actions (e.g. dim-warm + wait 15m + off) without blocking
 * the Sleep broadcast receiver. Cancelled when sleep tracking stops.
 */
object DelayedActionRunner {
    private const val TAG = "DelayedActionRunner"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val job = AtomicReference<Job?>(null)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun cancel() {
        job.getAndSet(null)?.cancel()
        Log.i(TAG, "Delayed action cancelled")
    }

    fun start(
        bulb: Bulb,
        action: LightAction,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        job.getAndSet(null)?.cancel()
        val newJob = scope.launch {
            val result = try {
                BulbController(bulb, appContext).runAction(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Result.failure(e)
            }
            if (result.isFailure &&
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException
            ) {
                Log.i(TAG, "Delayed action cancelled mid-run")
                return@launch
            }
            onFinished(result)
        }
        job.set(newJob)
        newJob.invokeOnCompletion {
            job.compareAndSet(newJob, null)
        }
    }

    val isRunning: Boolean
        get() = job.get()?.isActive == true
}
