package com.anishumar.kharcha.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Minimal daily-spend bar chart: black bars, today in the accent colour. */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values = DoubleArray(0)
    private var labels = emptyList<String>()
    private var highlight = -1
    private val d = resources.displayMetrics.density

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK }
    private val barDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F000000 }
    private val today = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.HAIRLINE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED; textSize = 11 * d; textAlign = Paint.Align.CENTER
        typeface = Ui.regular(context)
    }

    fun set(values: DoubleArray, labels: List<String>, highlightIndex: Int) {
        this.values = values; this.labels = labels; this.highlight = highlightIndex
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val bottomPad = 22 * d
        val h = height - bottomPad
        val slot = width.toFloat() / values.size
        val bw = (slot * 0.5f).coerceAtMost(14 * d)
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val r = bw / 2
        c.drawRect(0f, h, width.toFloat(), h + d * 0.5f, base)
        for (i in values.indices) {
            val cx = slot * i + slot / 2
            val bh = (values[i] / max * (h - 4 * d)).toFloat()
            if (bh > 0) {
                val p = when {
                    highlight < 0 -> bar
                    i == highlight -> today
                    else -> barDim
                }
                c.drawRoundRect(RectF(cx - bw / 2, h - bh.coerceAtLeast(bw), cx + bw / 2, h), r, r, p)
            } else c.drawCircle(cx, h - 2 * d, 1.5f * d, barDim)
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                text.color = if (i == highlight) Ui.INK else Ui.MUTED
                c.drawText(it, cx, height - 4 * d, text)
            }
        }
    }
}
