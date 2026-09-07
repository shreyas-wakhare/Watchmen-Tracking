package com.watchmen.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            Log.d("SmsReceiver", "SMS received (ignored).")
            // You DON'T need to process SMS, just receiving is enough.
        }
    }
}
