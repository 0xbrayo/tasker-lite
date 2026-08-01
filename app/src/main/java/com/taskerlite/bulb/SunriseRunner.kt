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
 * Runs dawn simulations without blocking the broadcast receiver.
 * Yeelight path finishes after one `start_cf`; miIO path may run ~30 minutes.
 */
object SunriseRunner {
    private const val TAG = "SunriseRunner"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val job = AtomicReference<Job?>(null)
    private val activeBulb = AtomicReference<Bulb?>(null)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun cancel() {
        val bulb = activeBulb.getAndSet(null)
        job.getAndSet(null)?.cancel()
        if (bulb != null) {
            scope.launch {
                runCatching {
                    BulbController(bulb, appContext).stopDawn()
                }
            }
        }
        Log.i(TAG, "Dawn cancelled")
    }

    fun start(
        bulb: Bulb,
        action: LightAction.Sunrise,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        job.getAndSet(null)?.cancel()
        activeBulb.set(bulb)
        val newJob = scope.launch {
            val result = try {
                BulbController(bulb, appContext).runSunrise(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Result.failure(e)
            }
            if (result.isFailure &&
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException
            ) {
                Log.i(TAG, "Dawn cancelled mid-run")
                activeBulb.compareAndSet(bulb, null)
                return@launch
            }
            activeBulb.compareAndSet(bulb, null)
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
