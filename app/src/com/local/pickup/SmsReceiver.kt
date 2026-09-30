package com.local.pickup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
                !Store.prefs(context).getBoolean("auto_sms", true)
        )
            return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val text = messages.joinToString("") { it.messageBody.orEmpty() }
        val time = messages.lastOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val added = Store.ingest(context, text, time)
        if (added > 0 && Store.prefs(context).getBoolean("notify", true))
            ReminderReceiver.notify(
                context,
                "新的快递",
                "识别到 $added 个取件码，当前 ${Store.pending(context)} 件待取",
            )
    }
}
