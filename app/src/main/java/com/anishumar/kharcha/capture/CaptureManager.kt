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
import com.anishumar.kharcha.data.Source
import com.anishumar.kharcha.ui.EditExpenseActivity
import com.anishumar.kharcha.ui.TransactionActivity
import kotlin.math.abs

/** Saves captured payments, merging duplicates that arrive via SMS + notification + bubble. */
object CaptureManager {

    /** Returns the existing expense this capture duplicates, if any. */
    fun findDuplicate(ctx: Context, e: Expense): Expense? {
        val db = ExpenseDb.get(ctx)
        if (!e.ref.isNullOrBlank()) db.byRef(e.ref)?.let { return it }
        val w = PaymentMatcher.DUP_WINDOW_MS
        // closest in time first, so a burst of same-amount payments pairs up correctly
        return db.between(e.timestamp - w, e.timestamp + w)
            .sortedBy { abs(it.timestamp - e.timestamp) }
            .firstOrNull { PaymentMatcher.isSamePayment(it, e) }
    }

    /** Better payee = a readable name rather than a raw VPA / blank. */
    private fun betterPayee(old: String, new: String): String = when {
        new.isBlank() || new == "Unknown" -> old
        old.isBlank() || old == "Unknown" -> new
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

    /**
     * Re-checks auto-logged rows with the current parser and duplicate rules:
     * drops ones that aren't payments, fills in payees, merges duplicates.
     * Rows with a note, and manual entries, are left alone.
     */
    @Synchronized
    fun repair(ctx: Context): Int {
        val db = ExpenseDb.get(ctx)
        var changed = 0
        val kept = mutableListOf<Expense>()
        fun withPayee(x: Expense, payee: String) = x.copy(
            payee = payee,
            category = if (x.category == "Other") Categories.guess(ctx, payee) else x.category
        )
        for (row in db.all().sortedBy { it.timestamp }) {
            var e = row
            val auto = e.source == Source.SMS || e.source == Source.NOTIFICATION
            val raw = e.raw
            if (auto && raw != null && e.note == null) {
                val p = PaymentParser.parse(raw)
                if (p == null) { db.delete(e.id); changed++; continue }
                if (p.payee.isNotBlank() && !PaymentParser.isValidPayee(e.payee)) e = withPayee(e, p.payee)
                // Learned rules come first in guess(), so a category the user picked sticks
                if (e.category == "Other" || e.category == "Transfers") e = e.copy(category = Categories.guess(ctx, e.payee))
            }
            val dup = if (e.source == Source.MANUAL) null
                else kept.lastOrNull { it.source != Source.MANUAL && PaymentMatcher.isSamePayment(it, e) }
            if (dup != null && e.note == null) {
                var merged = dup.copy(ref = dup.ref ?: e.ref)
                if (e.source == Source.SCREEN) merged = merged.copy(category = e.category)
                val payee = betterPayee(dup.payee, e.payee)
                if (payee != dup.payee) merged = withPayee(merged, payee)
                db.update(merged)
                kept[kept.indexOf(dup)] = merged
                db.delete(e.id); changed++
                continue
            }
            if (e != row) { db.update(e); changed++ }
            kept += e
        }
        return changed
    }

    private fun notifySaved(ctx: Context, e: Expense) {
        val open = Intent(ctx, EditExpenseActivity::class.java)
            .putExtra(EditExpenseActivity.EXTRA_ID, e.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, e.id.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val details = PendingIntent.getActivity(
            ctx, -e.id.toInt(),
            Intent(ctx, TransactionActivity::class.java)
                .putExtra(TransactionActivity.EXTRA_ID, e.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, KharchaApp.CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("${Money.format(e.amount)} · ${e.payee}")
            .setContentText("Logged as ${e.category}")
            .setContentIntent(details)
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
