package com.anishumar.kharcha.capture

import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.Source
import kotlin.math.abs

/**
 * Decides whether two captures (bubble, SMS, notification) are the same payment.
 * Pure Kotlin so it can be unit tested.
 */
object PaymentMatcher {

    const val DUP_WINDOW_MS = 15 * 60 * 1000L
    private const val SAME_AMOUNT_ANY_PAYEE_MS = 3 * 60 * 1000L

    private val upiRef = Regex("^\\d{12}$")

    fun isSamePayment(o: Expense, e: Expense): Boolean {
        if (abs(o.amount - e.amount) > 0.009) return false
        if (abs(o.timestamp - e.timestamp) > DUP_WINDOW_MS) return false
        // One capture's ref printed in the other's text, e.g. the UPI ref on the
        // success screen also appears in the bank SMS
        if (mentions(o.raw, e.ref) || mentions(e.raw, o.ref)) return true
        // Only two UPI refs (12-digit RRN) are comparable. A bank's own card ref, a
        // Google txn id and a UPI ref can all describe the same payment.
        if (o.ref != null && e.ref != null && upiRef.matches(o.ref) && upiRef.matches(e.ref) && o.ref != e.ref) return false
        if (o.source == Source.SMS && e.source == Source.SMS) {
            if (o.raw?.trim() == e.raw?.trim()) return true
            // Same alert format with different details = two payments (two ₹20 chais).
            // Different formats can be one payment: IDFC sends "spent at ANTHROPIC*"
            // and then "for Anthropic paid ... standing instruction" for one charge.
            if (template(o.raw) == template(e.raw)) return false
        }
        return similar(o.payee, e.payee) || abs(o.timestamp - e.timestamp) < SAME_AMOUNT_ANY_PAYEE_MS
    }

    private fun template(raw: String?): String =
        (raw ?: "").lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").take(40)

    private fun mentions(raw: String?, ref: String?): Boolean =
        !raw.isNullOrBlank() && !ref.isNullOrBlank() && raw.contains(ref)

    private fun similar(a: String, b: String): Boolean {
        val x = Categories.key(a)
        val y = Categories.key(b)
        // Card SMS often have no payee we can read
        if (x.isEmpty() || y.isEmpty() || x == "unknown" || y == "unknown") return true
        val n = minOf(5, x.length, y.length)
        return x.contains(y.take(n)) || y.contains(x.take(n))
    }
}
