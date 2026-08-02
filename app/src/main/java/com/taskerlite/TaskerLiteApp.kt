package com.taskerlite

import android.app.Application
import com.taskerlite.bulb.DelayedActionRunner
import com.taskerlite.bulb.SunriseRunner
import com.taskerlite.data.PreferencesRepository
import com.taskerlite.service.RoutineScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskerLiteApp : Application() {
    lateinit var repository: PreferencesRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = PreferencesRepository(this)
        SunriseRunner.init(this)
        DelayedActionRunner.init(this)
        // Arm sunset/fixed routines even if the Sleep automation service is off
        appScope.launch {
            runCatching { RoutineScheduler.rescheduleAll(this@TaskerLiteApp, repository) }
        }
    }
}
