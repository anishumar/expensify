package com.anishumar.kharcha.report

import com.anishumar.kharcha.data.Expense
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class PeriodType { WEEK, MONTH }

data class Period(val type: PeriodType, val start: Long, val end: Long) {
    fun label(): String {
        return if (type == PeriodType.MONTH) {
            SimpleDateFormat("MMMM yyyy", Locale("en", "IN")).format(start)
        } else {
            val f = SimpleDateFormat("d MMM", Locale("en", "IN"))
            "${f.format(start)} – ${f.format(end - 1)}"
        }
    }

    fun shift(by: Int): Period = of(type, Calendar.getInstance().apply {
        timeInMillis = start
        if (type == PeriodType.WEEK) add(Calendar.WEEK_OF_YEAR, by) else add(Calendar.MONTH, by)
    }.timeInMillis)

    /** Number of buckets in the daily chart. */
    fun days(): Int = ((end - start + 3_600_000) / 86_400_000L).toInt()

    companion object {
        /** Week = Monday..Sunday containing [t]; month = calendar month. */
        fun of(type: PeriodType, t: Long = System.currentTimeMillis()): Period {
            val c = Calendar.getInstance().apply {
                timeInMillis = t
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return if (type == PeriodType.WEEK) {
                val dow = c.get(Calendar.DAY_OF_WEEK)        // Sun=1..Sat=7
                val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
                c.add(Calendar.DAY_OF_MONTH, -back)
                val s = c.timeInMillis
                c.add(Calendar.DAY_OF_MONTH, 7)
                Period(type, s, c.timeInMillis)
            } else {
                c.set(Calendar.DAY_OF_MONTH, 1)
                val s = c.timeInMillis
                c.add(Calendar.MONTH, 1)
                Period(type, s, c.timeInMillis)
            }
        }
    }
}

data class Summary(
    val total: Double,
    val count: Int,
    val byCategory: List<Pair<String, Double>>,
    val topPayees: List<Pair<String, Double>>,
    val daily: DoubleArray,
    val dayLabels: List<String>
)

object Reports {
    fun summarize(p: Period, list: List<Expense>): Summary {
        val total = list.sumOf { it.amount }
        val byCat = list.groupBy { it.category }
            .map { (k, v) -> k to v.sumOf { it.amount } }
            .sortedByDescending { it.second }
        val top = list.groupBy { it.payee.lowercase() }
            .map { (_, v) -> v.first().payee to v.sumOf { it.amount } }
            .sortedByDescending { it.second }
            .take(5)
        val n = p.days()
        val daily = DoubleArray(n)
        val cal = Calendar.getInstance()
        for (e in list) {
            cal.timeInMillis = e.timestamp
            val idx = if (p.type == PeriodType.MONTH) cal.get(Calendar.DAY_OF_MONTH) - 1
            else ((e.timestamp - p.start) / 86_400_000L).toInt()
            if (idx in 0 until n) daily[idx] += e.amount
        }
        val labels = if (p.type == PeriodType.WEEK) listOf("M", "T", "W", "T", "F", "S", "S")
        else (1..n).map { if (it == 1 || it % 5 == 0) it.toString() else "" }
        return Summary(total, list.size, byCat, top, daily, labels)
    }
}
