package com.anishumar.kharcha.capture

import java.util.Calendar

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
        "\\b(debited|debit|paid|sent|spent|payment\\s+(?:of|to|successful|done)|purchase|withdrawn|txn\\s+(?:of|for)|transferred\\s+to|trf\\s+to|paying" +
            // credit card spends: "has been used for INR 500", "transaction of Rs 500", "charged"
            "|used\\s+(?:for|at)|transaction\\s+of|charged|swiped|thank\\s+you\\s+for\\s+using)\\b",
        opt
    )
    private val strongDebitWords = Regex(
        "\\b(debited|paid|sent|spent|charged|used\\s+(?:for|at)|transaction\\s+of)\\b", opt
    )
    // Paying the card bill moves money you already logged as card spends; counting it would double it
    private val cardBillPayment = Regex(
        "(?:towards|to)\\s+(?:your\\s+)?(?:[A-Za-z]+\\s+){0,3}credit\\s*card|credit\\s*card\\s+(?:bill|dues|outstanding|payment)",
        opt
    )
    private val creditWords = Regex(
        "\\b(received|credited\\s+to\\s+(?:your|a/c)|has\\s+been\\s+credited|refund|cashback|reversed)\\b",
        opt
    )
    private val junkWords = Regex(
        "\\b(otp|one[- ]time password|will be (?:debited|deducted|charged)|is due|due on|due date|due by|overdue|min(?:imum)? (?:amount )?due|total (?:amount )?due|" +
            "request(?:ed)? money|requested payment|has requested|collect request|requesting|mandate|autopay set|scheduled|" +
            "loan of|pre-approved|failed|declined|unsuccessful|pending)\\b",
        opt
    )

    private const val NAME = "([A-Za-z0-9][A-Za-z0-9@._&'()*\\- ]{1,48}?)"
    private const val STOP = "(?=\\s+(?:on|ref\\w*|upi|via|from|dated|at|using|with|thru|through|a/c|avl|bal|txn|not|for|is|was|successful\\w*|done|completed)\\b|\\s*[;,:\\n|]|\\.(?:\\s|$)|\\s*$)"

    private val payeePatterns = listOf(
        Regex("UPI/P2[MAP]/\\d+/([^/\\n]{2,40})", opt),                               // Axis style
        Regex("withdrawn\\s*@\\s*$NAME$STOP", opt),                                    // ATM: "withdrawn@ WASSEYPU on"
        Regex("\\bfor\\s+$NAME\\s+paid\\s+from\\b", opt),                              // IDFC SI: "INR 11999 for Anthropic paid from"
        Regex("\\byour\\s+([A-Za-z]+)\\s+order\\b", opt),                              // "Your Swiggy Order #123 of Rs 201"
        Regex("(?:paid|sent|payment|transferred|trf|paying)\\s+(?:successfully\\s+)?to\\s*:?\\s*(?:VPA\\s+)?$NAME$STOP", opt),
        Regex(";\\s*$NAME\\s+credited", opt),                                          // ICICI style
        Regex("\\bto\\s+(?:VPA\\s+)?$NAME$STOP", opt),
        Regex("\\bat\\s+$NAME$STOP", opt),                                            // card spends
        Regex("\\binfo[:\\s]+(?:UPI[-/])?$NAME$STOP", opt),
        Regex("\\bon\\s+\\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\\d{2,4}\\s+on\\s+$NAME$STOP", opt), // ICICI card: "on 30-Oct-26 on AMAZON"
        Regex("\\d{1,2}:\\d{2}(?::\\d{2})?\\s+IST\\s+(?!at\\s)$NAME(?=\\s+avl\\b)", opt)    // Axis card: "10:29:18 IST RAZ*Makemyt Avl Limit"
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
            if (cardBillPayment.containsMatchIn(oneLine)) return null
            val isDebit = debitWords.containsMatchIn(oneLine)
            if (!isDebit) return null
            // "credited" alone with no debit word was filtered above; a message with
            // only refund/received + a weak debit word is still most likely income
            if (creditWords.containsMatchIn(oneLine) && !strongDebitWords.containsMatchIn(oneLine)) return null
        }

        // On a checkout / order screen the first ₹ is often an item price: prefer the labelled total
        val amount = (if (lenient) findTotal(flat) else null) ?: findAmount(oneLine) ?: return null
        if (amount <= 0 || amount > 10_000_000) return null

        val payee = findPayee(flat) ?: ""
        val ref = findRef(oneLine)
        return Parsed(amount, cleanPayee(payee), ref)
    }

    private fun findAmount(s: String): Double? {
        for (m in currencyAmount.findAll(s)) {
            val before = s.substring(maxOf(0, m.range.first - 24), m.range.first).lowercase()
            if (before.contains("bal") || before.contains("limit") || before.contains("lmt") || before.contains("available") ||
                before.contains("cashback") || before.contains("reward") || before.contains("save")
            ) continue
            m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { return it }
        }
        bareDebitAmount.find(s)?.let { m ->
            return m.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        return null
    }

    // Strongest first. "Paid to" on GPay / PhonePe has the amount a line or two below it.
    private val totalLabels = listOf(
        "grand total", "total paid", "amount paid", "paid amount", "you paid", "total payable", "net payable",
        "amount payable", "to pay", "order total", "bill total", "total amount", "paid to", "paid via", "paid", "total"
    )

    /** True when the screen shows a "Grand total" / "To pay" style amount (not just item prices). */
    fun hasLabelledTotal(text: String) = findTotal(text) != null

    /**
     * Text recognition often reads "₹" as R, Z, ? or F. Put the rupee sign back when one of those
     * sits right before a number on its own ("To Pay  R344" → "To Pay  ₹344").
     */
    fun normalizeOcr(text: String) = text.replace(Regex("(^|[\\s(:])[RZ?F%]\\s?(?=\\d)", RegexOption.MULTILINE), "$1₹")

    /** The amount next to (or just below) a "Grand total" / "To pay" / "Amount paid" label. */
    private fun findTotal(text: String): Double? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var best: Pair<Int, Double>? = null
        for ((i, line) in lines.withIndex()) {
            val l = line.lowercase()
            if (Regex("sav|discount|cashback|\\boff\\b|item total|mrp|coupon|\\btip\\b").containsMatchIn(l)) continue
            val rank = totalLabels.indexOfFirst { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(l) }
            if (rank < 0 || (best != null && rank >= best.first)) continue
            // same line after the label, else one of the next two lines
            val after = line.substring(l.indexOf(totalLabels[rank]))
            val sameLine = currencyAmount.find(after)
                ?: Regex("\\s([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*$").find(after)   // ₹ lost by text recognition
            val below = (1..2).firstNotNullOfOrNull { k -> lines.getOrNull(i + k)?.let { currencyAmount.find(it) } }
            val amount = (sameLine ?: below)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            if (amount > 0) best = rank to amount
        }
        return best?.second
    }

    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?"
    private val dayMonthYear = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?$MON'?,?\\s*'?(\\d{4}|\\d{2}(?![:\\d]))?", opt)
    private val monthDayYear = Regex("\\b$MON\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![:\\d]),?\\s*(\\d{4})?", opt)
    private val numericDate = Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4}|\\d{2})\\b")
    private val relativeDay = Regex("\\b(today|yesterday)\\b", opt)
    private val clock = Regex("\\b(\\d{1,2})[:.](\\d{2})(?::\\d{2})?\\s*([ap])\\.?\\s?m\\b\\.?|\\b([01]?\\d|2[0-3]):([0-5]\\d)(?::[0-5]\\d)?\\b", opt)

    /**
     * When the payment happened, as shown on the screen: "31 Oct 2026, 11:48 am",
     * "11:48 am on 31 Oct", "Fri, 31 Oct'26, 11:48 AM", "Yesterday, 9:05 pm", "31/10/2026 11:48".
     * Returns null when the screen doesn't say, or says something implausible.
     */
    fun timestamp(text: String, now: Long = System.currentTimeMillis()): Long? {
        val flat = text.replace('\u00A0', ' ').replace('\n', ' ')
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }

        // (position, y, m, d) — y may be -1 when the screen leaves it out
        data class D(val at: IntRange, val y: Int, val m: Int, val d: Int)
        val dates = mutableListOf<D>()
        fun year(s: String?) = when {
            s.isNullOrEmpty() -> -1
            s.length == 2 -> 2000 + s.toInt()
            else -> s.toInt()
        }
        dayMonthYear.findAll(flat).forEach { dates += D(it.range, year(it.groupValues[3]), monthNames.indexOf(it.groupValues[2].lowercase().take(3)), it.groupValues[1].toInt()) }
        monthDayYear.findAll(flat).forEach { dates += D(it.range, year(it.groupValues[3]), monthNames.indexOf(it.groupValues[1].lowercase().take(3)), it.groupValues[2].toInt()) }
        numericDate.findAll(flat).forEach { dates += D(it.range, year(it.groupValues[3]), it.groupValues[2].toInt() - 1, it.groupValues[1].toInt()) }
        relativeDay.findAll(flat).forEach {
            val c = Calendar.getInstance().apply { timeInMillis = now; if (it.value.lowercase() == "yesterday") add(Calendar.DAY_OF_MONTH, -1) }
            dates += D(it.range, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }
        dates.retainAll { it.m in 0..11 && it.d in 1..31 }
        dates.sortBy { it.at.first }

        data class T(val at: IntRange, val h: Int, val min: Int)
        val times = clock.findAll(flat).mapNotNull { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) {
                val h = g[1].toInt(); val mi = g[2].toInt()
                if (h !in 1..12 || mi > 59) return@mapNotNull null
                val pm = g[3].equals("p", ignoreCase = true)
                T(m.range, (h % 12) + if (pm) 12 else 0, mi)
            } else T(m.range, g[4].toInt(), g[5].toInt())
        }.toList()

        fun gap(a: IntRange, b: IntRange) = maxOf(0, maxOf(a.first, b.first) - minOf(a.last, b.last))
        val date = dates.firstOrNull { d -> times.any { gap(it.at, d.at) <= 40 } } ?: dates.firstOrNull()
        val time = if (date != null) times.filter { gap(it.at, date.at) <= 40 }.minByOrNull { gap(it.at, date.at) } else times.firstOrNull()
        if (date == null && time == null) return null

        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (date != null) {
                set(Calendar.YEAR, if (date.y > 0) date.y else nowCal.get(Calendar.YEAR))
                set(Calendar.MONTH, date.m)
                set(Calendar.DAY_OF_MONTH, date.d)
            }
            if (time != null) { set(Calendar.HOUR_OF_DAY, time.h); set(Calendar.MINUTE, time.min) }
        }
        // No year on screen and that lands in the future → it was last year. Only a time → today, or yesterday if later than now.
        if (c.timeInMillis > now + 5 * 60_000) {
            if (date == null) c.add(Calendar.DAY_OF_MONTH, -1)
            else if (date.y <= 0) c.add(Calendar.YEAR, -1)
        }
        val t = c.timeInMillis
        return t.takeIf { it <= now + 5 * 60_000 && it >= now - 400L * 86_400_000 }
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

    fun isValidPayee(p: String) = p != "Unknown" && valid(p)

    private fun valid(c: String): Boolean {
        val t = c.trim()
        if (t.length < 2) return false
        if (badPayees.containsMatchIn(t)) return false
        if (Regex("^[\\d,.₹ ]+$").matches(t)) return false
        if (Regex("^\\+?\\d{6,}").containsMatchIn(t)) return false                    // phone / SMS short code
        if (t.startsWith("http", ignoreCase = true)) return false
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

    // ---------- extra details for the transaction page ----------

    private val banks = listOf(
        "\\bidfc\\b" to "IDFC FIRST Bank", "\\bhdfc\\b" to "HDFC Bank", "\\bicici\\b" to "ICICI Bank",
        "\\baxis\\b" to "Axis Bank", "\\bkotak\\b" to "Kotak", "\\bfederal\\b" to "Federal Bank",
        "\\bhsbc\\b" to "HSBC", "\\bsbi\\b|state bank" to "SBI", "\\byes\\s*bank\\b" to "Yes Bank",
        "\\bindusind\\b" to "IndusInd Bank", "\\bpnb\\b|punjab national" to "PNB",
        "bank of baroda" to "Bank of Baroda", "\\bcanara\\b" to "Canara Bank", "union bank" to "Union Bank",
        "\\bau\\s+(?:small finance\\s+)?bank|\\bau\\s+credit" to "AU Bank", "\\brbl\\b" to "RBL Bank",
        "\\bamex\\b|american express" to "Amex", "airtel payments" to "Airtel Payments Bank",
        "paytm payments" to "Paytm Payments Bank", "\\bonecard\\b" to "OneCard",
        "standard chartered" to "Standard Chartered", "\\bciti" to "Citi"
    ).map { (re, name) -> Regex(re, opt) to name }

    private val lastDigits = Regex(
        "(?:card|a/c|acct|account)(?:\\s*no\\.?)?(?:\\s*ending(?:\\s*with)?)?\\s*[:.]?\\s*[x*]*\\s*(\\d{3,})", opt
    )

    /** "Axis Bank Credit Card ••0622", "SBI A/c ••2209", or null if nothing is mentioned. */
    fun account(text: String): String? {
        val bank = banks.firstOrNull { it.first.containsMatchIn(text) }?.second
        val kind = when {
            Regex("credit\\s*card", opt).containsMatchIn(text) -> "Credit Card"
            Regex("debit\\s*card", opt).containsMatchIn(text) -> "Debit Card"
            Regex("\\bcard\\b", opt).containsMatchIn(text) -> "Card"
            Regex("\\b(a/c|acct|account)\\b", opt).containsMatchIn(text) -> "A/c"
            else -> null
        }
        val last = lastDigits.find(text)?.groupValues?.get(1)?.takeLast(4)
        if (bank == null && last == null) return null
        return listOfNotNull(bank, kind, last?.let { "••$it" }).joinToString(" ")
    }

    /** "UPI", "Credit card · AutoPay", "ATM withdrawal"… */
    fun method(text: String): String? {
        val base = when {
            Regex("\\bwithdrawn\\b|\\batm\\b", opt).containsMatchIn(text) -> "ATM withdrawal"
            Regex("\\bupi\\b", opt).containsMatchIn(text) -> "UPI"
            Regex("credit\\s*card", opt).containsMatchIn(text) -> "Credit card"
            Regex("debit\\s*card", opt).containsMatchIn(text) -> "Debit card"
            Regex("\\bcard\\b", opt).containsMatchIn(text) -> "Card"
            Regex("\\b(neft|imps|rtgs)\\b", opt).containsMatchIn(text) -> "Bank transfer"
            else -> return null
        }
        val auto = Regex("standing instruction|\\bautopay\\b|auto-?debit", opt).containsMatchIn(text)
        return if (auto) "$base · AutoPay" else base
    }

    /** The payee's UPI ID, e.g. "chai.point@ybl". Emails are skipped. */
    fun upiId(text: String): String? =
        Regex("(?<![\\w.@-])([A-Za-z0-9][\\w.\\-]+@[A-Za-z]{2,})(?![\\w.@])").find(text)?.groupValues?.get(1)

    fun cleanPayee(p: String): String {
        var s = p.trim().trimEnd('.', ',', ';', ':', '-', ' ')
        // Card gateway prefixes: "RAZ*Makemyt", "PAY*SWIGGY", "ANTHROPIC* CLAUDE SUB"
        s = s.replace(Regex("^[A-Za-z]{2,4}\\*\\s*(?=\\S)"), "").replace('*', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()
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
