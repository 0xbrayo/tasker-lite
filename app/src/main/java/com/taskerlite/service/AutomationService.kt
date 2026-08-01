package com.taskerlite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taskerlite.MainActivity
import com.taskerlite.R
import com.taskerlite.TaskerLiteApp
import com.taskerlite.sleep.SleepEventReceiver
import com.taskerlite.sleep.SleepEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a runtime BroadcastReceiver alive for Sleep as Android events.
 */
class AutomationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receiver: SleepEventReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerSleepReceiver()
        Log.i(TAG, "AutomationService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    (application as TaskerLiteApp).repository.setServiceEnabled(false)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSleepReceiver()
        scope.cancel()
        Log.i(TAG, "AutomationService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerSleepReceiver() {
        if (receiver != null) return
        val engine = RulesEngine((application as TaskerLiteApp).repository)
        val r = SleepEventReceiver(engine)
        val filter = IntentFilter().apply {
            SleepEvents.ALL_ACTIONS.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(
            this,
            r,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = r
        Log.i(TAG, "Registered Sleep receiver for ${SleepEvents.ALL_ACTIONS.size} actions")
    }

    private fun unregisterSleepReceiver() {
        receiver?.let {
            it.destroy()
            runCatching { unregisterReceiver(it) }
            receiver = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "AutomationService"
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.taskerlite.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, AutomationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }
    }
}
