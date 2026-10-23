
package com.example.pathfinderindoor

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SahayBattery"

        const val ACTION_SMS_SENT =
            "com.example.sahay.SMS_SENT"

        const val ACTION_SMS_DELIVERED =
            "com.example.sahay.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val phone = intent.getStringExtra("phone") ?: "unknown"

        when (intent.action) {
            ACTION_SMS_SENT -> {
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        Log.d(
                            TAG,
                            "SMS_SEND_SUCCESS: Android reports sending success for $phone"
                        )
                    }

                    SmsManager.RESULT_ERROR_NO_SERVICE -> {
                        Log.e(TAG, "SMS_SEND_FAILED: No cellular service for $phone")
                    }

                    SmsManager.RESULT_ERROR_RADIO_OFF -> {
                        Log.e(TAG, "SMS_SEND_FAILED: Radio is off for $phone")
                    }

                    SmsManager.RESULT_ERROR_NULL_PDU -> {
                        Log.e(TAG, "SMS_SEND_FAILED: Null PDU for $phone")
                    }

                    else -> {
                        Log.e(
                            TAG,
                            "SMS_SEND_FAILED: resultCode=$resultCode, phone=$phone"
                        )
                    }
                }
            }

            ACTION_SMS_DELIVERED -> {
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(
                        TAG,
                        "SMS_DELIVERED: Carrier reports delivery to $phone"
                    )
                } else {
                    Log.e(
                        TAG,
                        "SMS_DELIVERY_UNCONFIRMED: resultCode=$resultCode, phone=$phone"
                    )
                }
            }

            else -> {
                Log.w(TAG, "Unknown SMS status action: ${intent.action}")
            }
        }
    }
}
