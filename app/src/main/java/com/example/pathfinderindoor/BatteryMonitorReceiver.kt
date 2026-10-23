package com.example.pathfinderindoor

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONArray

class BatteryMonitorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SahayBattery"
        private const val PREFS_NAME = "EmergencySettings"
        private const val CONTACTS_KEY = "emergency_contacts_json"
        private const val ALERT_SENT_KEY = "battery_low_alert_sent"

        const val ACTION_CHECK_BATTERY =
            "com.example.sahay.CHECK_BATTERY"

        const val ACTION_TEST_LOW_BATTERY =
            "com.example.sahay.TEST_LOW_BATTERY"

        const val ACTION_SMS_SENT =
            "com.example.sahay.SMS_SENT"

        const val ACTION_SMS_DELIVERED =
            "com.example.sahay.SMS_DELIVERED"

        // Contacts whose SMS Android confirmed as sent in this low-battery cycle.
        private const val ALERTED_KEY = "battery_alerted_phones"
        private const val LAST_ATTEMPT_KEY = "battery_last_attempt_ms"

        // Minimum pause between automatic attempts, so a failing number
        // is not retried on every battery update.
        private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

        /** Call when the battery is back above the threshold. */
        fun resetAlertState(context: Context) {
            val prefs = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

            if (
                !prefs.getBoolean(ALERT_SENT_KEY, false) &&
                !prefs.contains(ALERTED_KEY) &&
                !prefs.contains(LAST_ATTEMPT_KEY)
            ) {
                return
            }

            prefs.edit()
                .putBoolean(ALERT_SENT_KEY, false)
                .remove(ALERTED_KEY)
                .remove(LAST_ATTEMPT_KEY)
                .apply()

            Log.d(TAG, "Battery recovered. Alert state reset.")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Receiver triggered: ${intent.action}")

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        when (intent.action) {
            Intent.ACTION_BATTERY_OKAY -> {
                prefs.edit()
                    .putBoolean(ALERT_SENT_KEY, false)
                    .apply()

                Log.d(TAG, "Battery okay. Alert flag reset.")
                return
            }

            ACTION_SMS_SENT, ACTION_SMS_DELIVERED -> {
                val phone = intent.getStringExtra("phone") ?: "unknown"

                if (intent.action == ACTION_SMS_SENT) {
                    if (resultCode == android.app.Activity.RESULT_OK) {
                        Log.d(TAG, "SMS_SEND_SUCCESS for $phone")

                        if (!intent.getBooleanExtra("test", false)) {
                            val alerted = prefs
                                .getStringSet(ALERTED_KEY, null)
                                ?.toMutableSet()
                                ?: mutableSetOf()

                            alerted.add(phone)

                            prefs.edit()
                                .putStringSet(ALERTED_KEY, alerted)
                                .apply()
                        }
                    } else {
                        Log.e(
                            TAG,
                            "SMS_SEND_FAILED for $phone; resultCode=$resultCode"
                        )

                        // The send failed, so allow the next check to retry
                        // instead of staying blocked until the battery recovers.
                        prefs.edit()
                            .putBoolean(ALERT_SENT_KEY, false)
                            .apply()
                    }
                } else {
                    if (resultCode == android.app.Activity.RESULT_OK) {
                        Log.d(TAG, "SMS_DELIVERED to $phone")
                    } else {
                        Log.e(
                            TAG,
                            "SMS_DELIVERY_UNCONFIRMED for $phone; resultCode=$resultCode"
                        )
                    }
                }
                return
            }

            Intent.ACTION_BATTERY_LOW,
            ACTION_CHECK_BATTERY,
            ACTION_TEST_LOW_BATTERY -> {
                val isTest = intent.action == ACTION_TEST_LOW_BATTERY

                if (isTest) {
                    // Tests bypass the "already sent" flag and never change it,
                    // so a test cannot block a real low-battery alert.
                    Log.d(TAG, "TEST: forcing low-battery alert path.")
                }

                if (!isTest && prefs.getBoolean(ALERT_SENT_KEY, false)) {
                    Log.d(TAG, "Alert already sent for this cycle.")
                    return
                }

                if (!isTest) {
                    val lastAttempt = prefs.getLong(LAST_ATTEMPT_KEY, 0L)

                    if (
                        System.currentTimeMillis() - lastAttempt <
                        RETRY_INTERVAL_MS
                    ) {
                        Log.d(TAG, "Last attempt was recent; waiting before retrying.")
                        return
                    }
                }

                if (
                    context.checkSelfPermission(Manifest.permission.SEND_SMS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "SEND_SMS permission is not granted.")
                    return
                }

                val savedContacts = prefs.getString(CONTACTS_KEY, null)

                if (savedContacts.isNullOrBlank()) {
                    Log.e(TAG, "No saved contacts found.")
                    return
                }

                val allContacts = try {
                    val array = JSONArray(savedContacts)

                    (0 until array.length())
                        .mapNotNull { index ->
                            val item = array.optJSONObject(index)
                                ?: return@mapNotNull null

                            item.optString("phone")
                                .trim()
                                .takeIf { it.isNotBlank() }
                        }
                        .distinct()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not parse saved contacts.", e)
                    return
                }

                if (allContacts.isEmpty()) {
                    Log.e(TAG, "Contact list is empty.")
                    return
                }

                // On a retry, skip contacts that already received this cycle's SMS.
                val alreadyAlerted: Set<String> = if (isTest) {
                    emptySet<String>()
                } else {
                    prefs.getStringSet(ALERTED_KEY, null) ?: emptySet<String>()
                }

                val contacts = allContacts.filter { it !in alreadyAlerted }

                if (contacts.isEmpty()) {
                    Log.d(TAG, "All contacts were already alerted this cycle.")

                    if (!isTest) {
                        prefs.edit()
                            .putBoolean(ALERT_SENT_KEY, true)
                            .apply()
                    }
                    return
                }

                val percent = if (isTest) {
                    5
                } else {
                    val batteryStatus = context.registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )

                    val level = batteryStatus?.getIntExtra(
                        BatteryManager.EXTRA_LEVEL, -1
                    ) ?: -1

                    val scale = batteryStatus?.getIntExtra(
                        BatteryManager.EXTRA_SCALE, -1
                    ) ?: -1

                    if (level >= 0 && scale > 0) level * 100 / scale else -1
                }

                Log.d(TAG, "Battery percentage: $percent%")

                if (percent < 0) {
                    Log.e(TAG, "Unable to determine battery percentage.")
                    return
                }

                if (percent > 15) {
                    Log.d(TAG, "Battery above 15%; no alert needed.")
                    return
                }

                val message = context.getString(
                    R.string.low_battery_sms,
                    percent
                )

                var submittedCount = 0

                if (!isTest) {
                    prefs.edit()
                        .putLong(LAST_ATTEMPT_KEY, System.currentTimeMillis())
                        .apply()
                }

                try {
                    val smsManager =
                        context.getSystemService(SmsManager::class.java)

                    if (smsManager == null) {
                        Log.e(TAG, "SmsManager is not available on this device.")
                        return
                    }

                    for (phone in contacts) {
                        try {
                            val parts = smsManager.divideMessage(message)
                            val sentIntents = ArrayList<PendingIntent>()
                            val deliveryIntents = ArrayList<PendingIntent>()

                            for (index in parts.indices) {
                                val sentIntent = Intent(
                                    context,
                                    BatteryMonitorReceiver::class.java
                                ).apply {
                                    action = ACTION_SMS_SENT
                                    putExtra("phone", phone)
                                    putExtra("test", isTest)
                                }

                                val deliveryIntent = Intent(
                                    context,
                                    BatteryMonitorReceiver::class.java
                                ).apply {
                                    action = ACTION_SMS_DELIVERED
                                    putExtra("phone", phone)
                                }

                                sentIntents.add(
                                    PendingIntent.getBroadcast(
                                        context,
                                        (phone + index).hashCode(),
                                        sentIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT or
                                                PendingIntent.FLAG_IMMUTABLE
                                    )
                                )

                                deliveryIntents.add(
                                    PendingIntent.getBroadcast(
                                        context,
                                        (phone + index + "_delivery").hashCode(),
                                        deliveryIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT or
                                                PendingIntent.FLAG_IMMUTABLE
                                    )
                                )
                            }

                            if (parts.size > 1) {
                                smsManager.sendMultipartTextMessage(
                                    phone,
                                    null,
                                    parts,
                                    sentIntents,
                                    deliveryIntents
                                )
                            } else {
                                smsManager.sendTextMessage(
                                    phone,
                                    null,
                                    message,
                                    sentIntents.first(),
                                    deliveryIntents.first()
                                )
                            }

                            submittedCount++
                            Log.d(TAG, "SMS request submitted for $phone")
                        } catch (e: Exception) {
                            Log.e(TAG, "SMS submission failed for a contact.", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not initialize SMS sending.", e)
                    return
                }

                if (submittedCount > 0) {
                    if (!isTest) {
                        prefs.edit()
                            .putBoolean(ALERT_SENT_KEY, true)
                            .apply()
                    }

                    Log.d(TAG, "SMS requests submitted: $submittedCount")
                } else {
                    Log.e(TAG, "No SMS requests were submitted.")
                }
            }

            else -> Log.d(TAG, "Ignoring unrelated broadcast.")
        }
    }
}