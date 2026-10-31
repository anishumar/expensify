package com.anishumar.kharcha.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.anishumar.kharcha.data.Source

/** Reads payment confirmations posted by UPI apps and bank apps. */
class PaymentNotificationListener : NotificationListenerService() {

    companion object {
        val PAYMENT_APPS = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                         // PhonePe
            "net.one97.paytm",                         // Paytm
            "in.org.npci.upiapp",                      // BHIM
            "com.dreamplug.androidapp",                // CRED
            "in.amazon.mShop.android.shopping",        // Amazon Pay
            "com.mobikwik_new",                        // MobiKwik
            "com.whatsapp",                            // WhatsApp Pay
            "money.super.payments",                    // super.money
            "com.naviapp",                             // Navi
            "com.sbi.upi",                             // SBI Pay
            "com.csam.icici.bank.imobile",             // iMobile
            "com.snapwork.hdfc",                       // HDFC
            "com.axis.mobile",                         // Axis
            "com.msf.kbank.mobile",                    // Kotak
            "com.sbi.lotusintouch"                     // YONO
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PAYMENT_APPS) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val body = listOf(title, if (big.length > text.length) big else text)
            .filter { it.isNotBlank() }.joinToString("\n")
        if (body.isBlank()) return
        // WhatsApp: only payment notifications, never chats
        if (sbn.packageName == "com.whatsapp" && !body.contains("₹")) return
        val ctx = applicationContext
        Thread {
            runCatching { CaptureManager.captureInBackground(ctx, body, Source.NOTIFICATION, sbn.postTime) }
        }.start()
    }
}
