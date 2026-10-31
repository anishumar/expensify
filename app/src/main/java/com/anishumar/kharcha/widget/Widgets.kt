package com.anishumar.kharcha.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.anishumar.kharcha.R
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.report.Period
import com.anishumar.kharcha.report.PeriodType
import com.anishumar.kharcha.report.Reports
import com.anishumar.kharcha.ui.EditExpenseActivity
import com.anishumar.kharcha.ui.MainActivity
import com.anishumar.kharcha.ui.Ui
import java.util.Calendar

/** Home-screen widgets: today's spend (2×2) and this week's spend with a daily chart (4×2). */
open class SpendWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = refreshAsync(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == Widgets.ACTION_REFRESH) refreshAsync(ctx)
    }

    private fun refreshAsync(ctx: Context) {
        val pending = goAsync()
        Thread {
            try { Widgets.refreshAll(ctx.applicationContext) } finally { pending.finish() }
        }.start()
    }
}

class TodayWidget : SpendWidget()
class WeekWidget : SpendWidget()

object Widgets {
    const val ACTION_REFRESH = "com.anishumar.kharcha.REFRESH_WIDGETS"
    private const val DAY = 86_400_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    private val task = Runnable {
        appCtx?.let { c -> Thread { runCatching { refreshAll(c) } }.start() }
    }

    /** Debounced, so an SMS import of 100 rows redraws once. */
    fun requestRefresh(ctx: Context) {
        appCtx = ctx.applicationContext
        handler.removeCallbacks(task)
        handler.postDelayed(task, 700)
    }

    @Synchronized
    fun refreshAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        val today = m.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java))
        val week = m.getAppWidgetIds(ComponentName(ctx, WeekWidget::class.java))
        today.forEach { m.updateAppWidget(it, todayViews(ctx)) }
        week.forEach { m.updateAppWidget(it, weekViews(ctx, m.getAppWidgetOptions(it))) }
        if (today.isNotEmpty() || week.isNotEmpty()) scheduleMidnight(ctx)
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun todayViews(ctx: Context): RemoteViews {
        val db = ExpenseDb.get(ctx)
        val start = startOfDay(System.currentTimeMillis())
        val end = startOfDay(start + DAY + 3_600_000)
        val list = db.spending(start, end)
        val yesterday = db.spending(startOfDay(start - 3_600_000), start).sumOf { it.amount }
        val rv = RemoteViews(ctx.packageName, R.layout.widget_today)
        rv.setTextViewText(R.id.w_amount, Money.format(list.sumOf { it.amount }))
        rv.setTextViewText(
            R.id.w_sub,
            when (list.size) {
                0 -> "No spends yet"
                1 -> "1 payment"
                else -> "${list.size} payments"
            }
        )
        rv.setTextViewText(
            R.id.w_last,
            list.firstOrNull()?.let { "${it.payee}  ${Money.format(it.amount)}" } ?: ""
        )
        rv.setTextViewText(R.id.w_compare, "Yesterday ${Money.format(yesterday)}")
        clicks(ctx, rv, PeriodType.WEEK)
        return rv
    }

    private fun weekViews(ctx: Context, options: Bundle): RemoteViews {
        val db = ExpenseDb.get(ctx)
        val p = Period.of(PeriodType.WEEK)
        val list = db.spending(p.start, p.end)
        val s = Reports.summarize(p, list)
        val prev = p.shift(-1).let { q -> db.spending(q.start, q.end).sumOf { it.amount } }
        val rv = RemoteViews(ctx.packageName, R.layout.widget_week)
        rv.setTextViewText(R.id.w_title, "This week · ${p.label()}")
        rv.setTextViewText(R.id.w_amount, Money.format(s.total))
        if (prev > 0) {
            val pct = ((s.total - prev) / prev * 100).toInt()
            rv.setTextViewText(R.id.w_change, if (pct >= 0) "↑ $pct%" else "↓ ${-pct}%")
            rv.setTextColor(R.id.w_change, if (pct >= 0) Ui.ACCENT else Ui.MUTED)
        } else rv.setTextViewText(R.id.w_change, "${s.count} payments")
        rv.setTextViewText(
            R.id.w_top,
            s.byCategory.firstOrNull()?.let { (cat, amt) -> "Most on $cat  ${Money.format(amt)}" } ?: ""
        )

        val d = ctx.resources.displayMetrics.density
        val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val todayIdx = ((System.currentTimeMillis() - p.start) / DAY).toInt()
        rv.setImageViewBitmap(R.id.w_chart, chart(s.daily, s.dayLabels, todayIdx, ((wDp - 28) * d).toInt(), (58 * d).toInt(), d))
        clicks(ctx, rv, PeriodType.WEEK)
        return rv
    }

    /** Same look as the in-app BarChartView, drawn to a bitmap because widgets can't host custom views. */
    private fun chart(values: DoubleArray, labels: List<String>, highlight: Int, w: Int, h: Int, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
        val barDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK }
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.HAIRLINE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.MUTED; textSize = 10 * d; textAlign = Paint.Align.CENTER
        }
        val bottom = h - 14 * d
        val slot = w.toFloat() / values.size
        val bw = (slot * 0.4f).coerceAtMost(10 * d)
        val r = bw / 2
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        c.drawRect(0f, bottom, w.toFloat(), bottom + d * 0.5f, base)
        for (i in values.indices) {
            val cx = slot * i + slot / 2
            val bh = (values[i] / max * (bottom - 2 * d)).toFloat()
            if (bh > 0) {
                val p = if (i == highlight) bar else barDim
                c.drawRoundRect(RectF(cx - bw / 2, bottom - bh.coerceAtLeast(bw), cx + bw / 2, bottom), r, r, p)
            }
            labels.getOrNull(i)?.let { c.drawText(it, cx, h - 2 * d, text) }
        }
        return bmp
    }

    private fun clicks(ctx: Context, rv: RemoteViews, period: PeriodType) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PERIOD, period.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        rv.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 1, open, flags))
        val add = Intent(ctx, EditExpenseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        rv.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(ctx, 2, add, flags))
    }

    /** Roll "today" and "this week" over at midnight. */
    private fun scheduleMidnight(ctx: Context) {
        val next = startOfDay(startOfDay(System.currentTimeMillis()) + DAY + 3_600_000) + 5_000
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, TodayWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, next, pi)
    }
}
