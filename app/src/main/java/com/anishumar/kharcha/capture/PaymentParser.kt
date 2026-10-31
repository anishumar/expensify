package com.anishumar.kharcha.capture

/**
 * Pure-Kotlin parser (no Android deps) that pulls amount / payee / reference
 * out of payment-app screens, UPI app notifications and Indian bank SMS.
 */
object PaymentParser {

    data class Parsed(val amount: Double, val payee: String, val ref: String?)

    private val opt = setOf(RegexOption.IGNORE_CASE)

    // ₹250, Rs.250, Rs 1,250.50, INR 250.00
    private val currencyAmount =
        Regex("(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", opt)

    // "debited by 250.0" (SBI) / "debited for 250" without a currency sign
    private val bareDebitAmount =
        Regex("(?:debited|deducted|paid|sent|spent)\\s+(?:by|for|of|with)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\b", opt)

    private val debitWords = Regex(
        "\\b(debited|debit|paid|sent|spent|payment\\s+(?:of|to|successful|done)|purchase|withdrawn|txn\\s+of|transferred\\s+to|trf\\s+to|paying)\\b",
        opt
    )
    private val creditWords = Regex(
        "\\b(received|credited\\s+to\\s+(?:your|a/c)|has\\s+been\\s+credited|refund|cashback|reversed)\\b",
        opt
    )
    private val junkWords = Regex(
        "\\b(otp|one[- ]time password|will be debited|is due|due on|due date|request(?:ed)? money|collect request|requesting|mandate|autopay set|failed|declined|unsuccessful|pending)\\b",
        opt
    )

    private const val NAME = "([A-Za-z0-9][A-Za-z0-9@._&'()\\- ]{1,48}?)"
    private const val STOP = "(?=\\s+(?:on|ref\\w*|upi|via|from|dated|at|using|with|thru|through|a/c|avl|bal|txn|not|for|is|was|successful\\w*|done|completed)\\b|\\s*[;,:\\n|]|\\.(?:\\s|$)|\\s*$)"

    private val payeePatterns = listOf(
        Regex("UPI/P2[MAP]/\\d+/([^/\\n]{2,40})", opt),                               // Axis style
        Regex("(?:paid|sent|payment|transferred|trf|paying)\\s+(?:successfully\\s+)?to\\s*:?\\s*(?:VPA\\s+)?$NAME$STOP", opt),
        Regex(";\\s*$NAME\\s+credited", opt),                                          // ICICI style
        Regex("\\bto\\s+(?:VPA\\s+)?$NAME$STOP", opt),
        Regex("\\bat\\s+$NAME$STOP", opt),                                            // card spends
        Regex("\\binfo[:\\s]+(?:UPI[-/])?$NAME$STOP", opt)
    )

    private val badPayees = Regex(
        "^(you|your|a/c|ac|account|bank|block|report|the|this|be|us|call|sms|stop|card|upi|beneficiary|merchant|recipient|unknown)\\b",
        opt
    )

    private val refPatterns = listOf(
        Regex("\\b(?!91)(\\d{12})\\b"),
        Regex("(?:upi\\s*(?:ref|transaction|txn)?\\s*(?:no|id|number)?|ref(?:erence)?\\s*(?:no|id|number)?|refno|utr(?:\\s*no)?|rrn|txn\\s*(?:id|no)|transaction\\s*(?:id|no))\\s*[:.#-]?\\s*([A-Za-z0-9]{8,24})", opt)
    )

    /**
     * @param lenient true when the user explicitly tapped the bubble — accept the
     *                screen even if it doesn't contain a "paid/debited" keyword.
     */
    fun parse(text: String, lenient: Boolean = false): Parsed? {
        if (text.isBlank()) return null
        val flat = text.replace('\u00A0', ' ')
        val oneLine = flat.replace(Regex("\\s*\\n\\s*"), " \n ")

        if (!lenient) {
            if (junkWords.containsMatchIn(oneLine)) return null
            val isDebit = debitWords.containsMatchIn(oneLine)
            if (!isDebit) return null
            // "credited" alone with no debit word was filtered above; a message with
            // only refund/received + a weak debit word is still most likely income
            if (creditWords.containsMatchIn(oneLine) &&
                !Regex("\\b(debited|paid|sent|spent)\\b", opt).containsMatchIn(oneLine)
            ) return null
        }

        val amount = findAmount(oneLine) ?: return null
        if (amount <= 0 || amount > 10_000_000) return null

        val payee = findPayee(flat) ?: ""
        val ref = findRef(oneLine)
        return Parsed(amount, cleanPayee(payee), ref)
    }

    private fun findAmount(s: String): Double? {
        for (m in currencyAmount.findAll(s)) {
            val before = s.substring(maxOf(0, m.range.first - 24), m.range.first).lowercase()
            if (before.contains("bal") || before.contains("limit") || before.contains("available") ||
                before.contains("cashback") || before.contains("reward") || before.contains("save")
            ) continue
            m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { return it }
        }
        bareDebitAmount.find(s)?.let { m ->
            return m.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        return null
    }

    private fun findPayee(text: String): String? {
        // Payment app screens often split "Paid to" and the name on separate lines
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, line) in lines.withIndex()) {
            val l = line.lowercase().trimEnd(':', ' ')
            if (l in setOf("paid to", "sent to", "to", "payment to", "paying", "paid successfully to", "transferred to", "banking name")) {
                lines.getOrNull(i + 1)?.let { next ->
                    if (!next.startsWith("₹") && valid(next)) return next
                }
            }
        }
        val oneLine = text.replace(Regex("\\s*\\n\\s*"), " \n ")
        for (p in payeePatterns) {
            for (m in p.findAll(oneLine)) {
                val c = m.groupValues[1].trim()
                if (valid(c)) return c
            }
        }
        return null
    }

    private fun valid(c: String): Boolean {
        val t = c.trim()
        if (t.length < 2) return false
        if (badPayees.containsMatchIn(t)) return false
        if (Regex("^[\\d,.₹ ]+$").matches(t)) return false
        if (Regex("^(x+|\\*+)\\d+", opt).containsMatchIn(t)) return false   // masked a/c
        return true
    }

    private fun findRef(s: String): String? {
        for (p in refPatterns) {
            p.find(s)?.let { m ->
                val r = m.groupValues[1]
                if (r.any { it.isDigit() }) return r
            }
        }
        return null
    }

    fun cleanPayee(p: String): String {
        var s = p.trim().trimEnd('.', ',', ';', ':', '-', ' ')
        s = s.replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return ""
        // Shout-case bank SMS names → Title Case
        if (s == s.uppercase() && !s.contains("@")) {
            s = s.lowercase().split(" ").joinToString(" ") { w ->
                w.replaceFirstChar { it.titlecase() }
            }
        }
        return s.take(48)
    }
}
