package com.example.pathfinderindoor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a battery listener alive in the background so the low-battery SMS is
 * sent as soon as the level reaches 15% or below, instead of waiting for the
 * periodic worker. BatteryCheckWorker stays in place as a backup.
 */
class BatteryMonitorService : Service() {

    companion object {
        private const val TAG = "SahayBattery"
        private const val CHANNEL_ID = "sahay_battery_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "EmergencySettings"
        private const val ALERT_SENT_KEY = "battery_low_alert_sent"
        private const val LOW_BATTERY_PERCENT = 15

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
        }
    }

    private var receiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            if (level < 0 || scale <= 0) return

            val percent = level * 100 / scale

            if (percent > LOW_BATTERY_PERCENT) {
                // Battery recovered: allow a new alert on the next drop.
                BatteryMonitorReceiver.resetAlertState(context)
                return
            }

            val prefs = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

            // Battery updates arrive often. Stay quiet once this cycle's
            // alert has been sent.
            if (prefs.getBoolean(ALERT_SENT_KEY, false)) return

            Log.d(TAG, "Service: battery at $percent%; checking alert.")

            BatteryMonitorReceiver().onReceive(
                context,
                Intent(BatteryMonitorReceiver.ACTION_CHECK_BATTERY)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // The current level is delivered right away, so an already-low
        // battery is handled as soon as the service starts.
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true

        Log.d(TAG, "Battery monitor service started.")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Battery receiver was not registered.", e)
            }
            receiverRegistered = false
        }

        Log.d(TAG, "Battery monitor service stopped.")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery monitoring",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Sahay is watching your battery")
            .setContentText(
                "Emergency contacts are alerted if it drops to " +
                        "$LOW_BATTERY_PERCENT% or below."
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}