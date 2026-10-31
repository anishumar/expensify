package com.anishumar.kharcha.data

import java.text.NumberFormat
import java.util.Locale

object Money {
    private val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 2
    }

    fun format(v: Double): String {
        val s = fmt.format(v)
        return if (s.endsWith(".00")) s.dropLast(3) else s
    }
}
