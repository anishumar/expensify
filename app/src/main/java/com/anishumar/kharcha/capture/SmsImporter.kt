package com.anishumar.kharcha.capture

import android.content.Context
import android.provider.Telephony
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Source

object SmsImporter {

    /**
     * Indian bank/UPI SMS come from DLT headers like "VM-HDFCBK", "AX-SBIUPI-S",
     * "JD-ICICIT". Personal numbers are digits only, so skip those.
     */
    fun looksLikeBank(sender: String): Boolean {
        val s = sender.uppercase()
        if (s.isBlank()) return false
        if (Regex("^\\+?\\d+$").matches(s)) return false
        return true
    }

    /** Back-fill expenses from SMS already in the inbox. Returns how many were added. */
    fun importInbox(ctx: Context, days: Int = 90): Int {
        val since = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val db = ExpenseDb.get(ctx)
        var added = 0
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC"
        ) ?: return 0
        cursor.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                val ts = c.getLong(2)
                if (!looksLikeBank(addr)) continue
                val p = PaymentParser.parse(body) ?: continue
                val payee = p.payee.ifBlank { "Unknown" }
                val e = Expense(
                    amount = p.amount, payee = payee, category = Categories.guess(ctx, payee),
                    source = Source.SMS, ref = p.ref, timestamp = ts, raw = body
                )
                // same lock as live captures, so an SMS arriving mid-import isn't saved twice
                synchronized(CaptureManager) {
                    if (CaptureManager.findDuplicate(ctx, e) == null) {
                        db.insert(e); added++
                    }
                }
            }
        }
        return added
    }
}
