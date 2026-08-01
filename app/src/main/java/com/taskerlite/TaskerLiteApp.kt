package com.taskerlite

import android.app.Application
import com.taskerlite.bulb.SunriseRunner
import com.taskerlite.data.PreferencesRepository

class TaskerLiteApp : Application() {
    lateinit var repository: PreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PreferencesRepository(this)
        SunriseRunner.init(this)
    }
}
