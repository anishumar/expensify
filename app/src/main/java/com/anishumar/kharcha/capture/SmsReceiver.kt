package com.anishumar.kharcha.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.anishumar.kharcha.data.Source

/** Bank debit SMS → expense. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].originatingAddress ?: ""
        if (!SmsImporter.looksLikeBank(sender)) return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val ts = parts[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val pending = goAsync()
        Thread {
            try {
                CaptureManager.captureInBackground(context.applicationContext, body, Source.SMS, ts)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
