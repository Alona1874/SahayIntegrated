package com.example.pathfinderindoor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result



class BatteryCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {


    override fun doWork(): androidx.work.ListenableWorker.Result {
        Log.d("SahayBattery", "Battery worker started")

        val context = applicationContext
        return try {

            val battery = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val level = battery?.getIntExtra(
                BatteryManager.EXTRA_LEVEL, -1
            ) ?: -1

            val scale = battery?.getIntExtra(
                BatteryManager.EXTRA_SCALE, -1
            ) ?: -1

            if (level < 0 || scale <= 0) {
                Log.e("SahayBattery", "Unable to read battery level.")
                return androidx.work.ListenableWorker.Result.retry()
            }

            val percent = level * 100 / scale
            Log.d("SahayBattery", "Periodic battery check: $percent%")

            if (percent > 15) {
                BatteryMonitorReceiver.resetAlertState(context)

                Log.d(
                    "SahayBattery",
                    "Battery recovered above 15%; alert flag reset."
                )
            } else {
                Log.d(
                    "SahayBattery",
                    "Battery at or below 15%; checking alert."
                )

                BatteryMonitorReceiver().onReceive(
                    context,
                    Intent(BatteryMonitorReceiver.ACTION_CHECK_BATTERY)
                )
            }

            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e("SahayBattery", "Battery check failed.", e)
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}


