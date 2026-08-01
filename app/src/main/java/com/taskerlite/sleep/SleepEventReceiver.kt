package com.taskerlite.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskerlite.TaskerLiteApp
import com.taskerlite.service.RulesEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Handles Sleep as Android events.
 *
 * - Registered at runtime by [com.taskerlite.service.AutomationService] for implicit
 *   broadcasts from Sleep as Android.
 * - Also declared in the manifest (exported) so `adb shell am broadcast -p com.taskerlite`
 *   can deliver explicit package-targeted intents for testing.
 */
class SleepEventReceiver : BroadcastReceiver {

    /** Runtime registration path supplies the engine; manifest path builds one from Application. */
    private val injectedEngine: RulesEngine?

    constructor() : super() {
        injectedEngine = null
    }

    constructor(rulesEngine: RulesEngine) : super() {
        injectedEngine = rulesEngine
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        val event = SleepEvents.resolve(action)
        Log.i(TAG, "Received intent action=$action event=$event")

        val engine = injectedEngine
            ?: (context.applicationContext as? TaskerLiteApp)?.let { RulesEngine(it.repository) }

        if (engine == null) {
            Log.e(TAG, "No RulesEngine available")
            return
        }

        if (event == null) {
            if (action?.contains("urbandroid.sleep") == true) {
                val pending = goAsync()
                scope.launch {
                    try {
                        engine.logUnknown(action)
                    } finally {
                        pending.finish()
                    }
                }
            }
            return
        }

        val pending = goAsync()
        scope.launch {
            try {
                engine.handle(event)
            } finally {
                pending.finish()
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "SleepEventReceiver"
    }
}
