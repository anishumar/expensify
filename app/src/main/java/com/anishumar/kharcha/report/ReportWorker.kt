package com.anishumar.kharcha.report

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.anishumar.kharcha.KharchaApp
import com.anishumar.kharcha.R
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.ui.MainActivity

/**
 * Runs every few hours. Once a week (Mon–Sun) or month has finished, posts a
 * summary notification for it — exactly once per period.
 */
class ReportWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("kharcha", Context.MODE_PRIVATE)

        val lastWeek = Period.of(PeriodType.WEEK).shift(-1)
        if (prefs.getLong("reported_week", 0) < lastWeek.start) {
            post(ctx, lastWeek, "Weekly report", 1)
            prefs.edit().putLong("reported_week", lastWeek.start).apply()
        }
        val lastMonth = Period.of(PeriodType.MONTH).shift(-1)
        if (prefs.getLong("reported_month", 0) < lastMonth.start) {
            post(ctx, lastMonth, "Monthly report", 2)
            prefs.edit().putLong("reported_month", lastMonth.start).apply()
        }
        return Result.success()
    }

    private fun post(ctx: Context, p: Period, title: String, id: Int) {
        val db = ExpenseDb.get(ctx)
        val list = db.between(p.start, p.end)
        if (list.isEmpty()) return
        val s = Reports.summarize(p, list)
        val prev = db.between(p.shift(-1).start, p.shift(-1).end).sumOf { it.amount }
        val change = if (prev > 0) {
            val pct = ((s.total - prev) / prev * 100).toInt()
            if (pct >= 0) " (▲$pct% vs previous)" else " (▼${-pct}% vs previous)"
        } else ""
        val lines = s.byCategory.take(4).joinToString("\n") { (c, v) ->
            "${Categories.emoji[c] ?: ""} $c: ${Money.format(v)}"
        }
        val pi = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PERIOD, p.type.name)
                .putExtra(MainActivity.EXTRA_START, p.start),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "${p.label()}: ${Money.format(s.total)} across ${s.count} payments$change"
        val n = NotificationCompat.Builder(ctx, KharchaApp.CH_REPORT)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("$title · ${Money.format(s.total)}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\n$lines"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) { }
    }
}
