package com.taskerlite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskerlite.TaskerLiteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts the automation service after device reboot if it was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? TaskerLiteApp
                val repo = app?.repository
                if (repo != null) {
                    // Re-arm sunset / fixed-time routines after reboot
                    runCatching {
                        RoutineScheduler.rescheduleAll(context.applicationContext, repo)
                    }
                    if (repo.getState().serviceEnabled) {
                        Log.i(TAG, "Restarting AutomationService after boot")
                        AutomationService.start(context.applicationContext)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
