package com.anishumar.kharcha.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Minimal daily-spend bar chart. */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values = DoubleArray(0)
    private var labels = emptyList<String>()
    private var highlight = -1
    private val d = resources.displayMetrics.density

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F766E.toInt() }
    private val barDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF99D5CF.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F5F9.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt(); textSize = 11 * d; textAlign = Paint.Align.CENTER
    }

    fun set(values: DoubleArray, labels: List<String>, highlightIndex: Int) {
        this.values = values; this.labels = labels; this.highlight = highlightIndex
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val bottomPad = 18 * d
        val h = height - bottomPad
        val slot = width.toFloat() / values.size
        val bw = (slot * 0.62f).coerceAtMost(28 * d)
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val r = bw / 2.5f
        for (i in values.indices) {
            val cx = slot * i + slot / 2
            c.drawRoundRect(RectF(cx - bw / 2, 4 * d, cx + bw / 2, h), r, r, track)
            val bh = (values[i] / max * (h - 4 * d)).toFloat()
            if (bh > 0) {
                val p = if (highlight < 0 || i == highlight) bar else barDim
                c.drawRoundRect(RectF(cx - bw / 2, h - bh.coerceAtLeast(r * 2), cx + bw / 2, h), r, r, p)
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                c.drawText(it, cx, height - 4 * d, text)
            }
        }
    }
}
