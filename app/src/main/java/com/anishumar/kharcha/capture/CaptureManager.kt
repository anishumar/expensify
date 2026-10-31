package com.anishumar.kharcha.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.anishumar.kharcha.KharchaApp
import com.anishumar.kharcha.R
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.ui.EditExpenseActivity
import kotlin.math.abs

/** Saves captured payments, merging duplicates that arrive via SMS + notification + bubble. */
object CaptureManager {

    private const val DUP_WINDOW_MS = 15 * 60 * 1000L
    private const val SAME_AMOUNT_ANY_PAYEE_MS = 3 * 60 * 1000L

    /** Returns the existing expense this capture duplicates, if any. */
    fun findDuplicate(ctx: Context, e: Expense): Expense? {
        val db = ExpenseDb.get(ctx)
        if (!e.ref.isNullOrBlank()) db.byRef(e.ref)?.let { return it }
        val near = db.between(e.timestamp - DUP_WINDOW_MS, e.timestamp + DUP_WINDOW_MS)
        return near.firstOrNull { o ->
            if (abs(o.amount - e.amount) > 0.009) return@firstOrNull false
            if (!o.ref.isNullOrBlank() && !e.ref.isNullOrBlank() && o.ref != e.ref) return@firstOrNull false
            similar(o.payee, e.payee) || abs(o.timestamp - e.timestamp) < SAME_AMOUNT_ANY_PAYEE_MS
        }
    }

    private fun similar(a: String, b: String): Boolean {
        val x = Categories.key(a)
        val y = Categories.key(b)
        if (x.isEmpty() || y.isEmpty()) return true
        val n = minOf(5, x.length, y.length)
        return x.contains(y.take(n)) || y.contains(x.take(n))
    }

    /** Better payee = a readable name rather than a raw VPA / blank. */
    private fun betterPayee(old: String, new: String): String = when {
        new.isBlank() -> old
        old.isBlank() -> new
        old.contains("@") && !new.contains("@") -> new
        else -> old
    }

    /**
     * Background capture (SMS / notification): auto-categorise, save, and post a
     * notification the user can tap to change the category.
     */
    @Synchronized
    fun captureInBackground(ctx: Context, text: String, source: String, ts: Long = System.currentTimeMillis()) {
        val p = PaymentParser.parse(text) ?: return
        val payee = p.payee.ifBlank { "Unknown" }
        val draft = Expense(
            amount = p.amount, payee = payee, category = Categories.guess(ctx, payee),
            source = source, ref = p.ref, timestamp = ts, raw = text
        )
        val db = ExpenseDb.get(ctx)
        val dup = findDuplicate(ctx, draft)
        if (dup != null) {
            val merged = dup.copy(
                payee = betterPayee(dup.payee, draft.payee).let { if (it == "Unknown") dup.payee else it },
                ref = dup.ref ?: draft.ref
            )
            if (merged != dup) db.update(merged)
            return
        }
        val id = db.insert(draft)
        notifySaved(ctx, draft.copy(id = id))
    }

    /** Save from the bubble's confirm card (user already chose the category). */
    @Synchronized
    fun saveConfirmed(ctx: Context, e: Expense): Long {
        val db = ExpenseDb.get(ctx)
        db.saveRule(Categories.key(e.payee), e.category)
        val dup = findDuplicate(ctx, e)
        return if (dup != null) {
            db.update(
                dup.copy(
                    payee = if (e.payee.isNotBlank() && e.payee != "Unknown") e.payee else dup.payee,
                    category = e.category,
                    ref = dup.ref ?: e.ref
                )
            )
            cancelNotification(ctx, dup.id)
            dup.id
        } else db.insert(e)
    }

    private fun notifySaved(ctx: Context, e: Expense) {
        val open = Intent(ctx, EditExpenseActivity::class.java)
            .putExtra(EditExpenseActivity.EXTRA_ID, e.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, e.id.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val emoji = Categories.emoji[e.category] ?: ""
        val n = NotificationCompat.Builder(ctx, KharchaApp.CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("${Money.format(e.amount)} · ${e.payee}")
            .setContentText("Logged as $emoji ${e.category}. Tap to change.")
            .setContentIntent(pi)
            .addAction(0, "Change category", pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(notifId(e.id), n)
        } catch (_: SecurityException) { }
    }

    fun cancelNotification(ctx: Context, id: Long) {
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(id))
    }

    private fun notifId(id: Long) = 10_000 + id.toInt()
}
