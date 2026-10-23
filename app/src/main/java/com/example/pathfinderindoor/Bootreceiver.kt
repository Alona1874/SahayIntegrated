package com.example.pathfinderindoor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the battery monitor service after the phone reboots. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            BatteryMonitorService.start(context)
        } catch (e: Exception) {
            Log.e("SahayBattery", "Could not start monitor after boot.", e)
        }
    }
}