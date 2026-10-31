package com.anishumar.kharcha.data

data class Expense(
    val id: Long = 0,
    val amount: Double,
    val payee: String,
    val category: String,
    val source: String,          // SCREEN, SMS, NOTIFICATION, MANUAL
    val ref: String? = null,     // UPI ref / UTR / txn id
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    val raw: String? = null      // text it was parsed from (for debugging)
) {
    /** Lending and paying back a loan move money, but aren't spending. */
    val isSpending get() = !Categories.isLoan(category)
}

/** One line in the lend & borrow ledger. */
data class LedgerEntry(
    val id: Long = 0,
    val person: String,
    val amount: Double,
    val kind: String,            // Ledger.LENT, BORROWED, GOT_BACK, PAID_BACK
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    val expenseId: Long? = null  // the payment this came from, if it was a logged expense
)

object Ledger {
    const val LENT = "LENT"
    const val BORROWED = "BORROWED"
    const val GOT_BACK = "GOT_BACK"
    const val PAID_BACK = "PAID_BACK"

    /** Positive: they owe you. Negative: you owe them. */
    fun balance(entries: List<LedgerEntry>) = entries.sumOf {
        when (it.kind) {
            LENT, PAID_BACK -> it.amount
            else -> -it.amount
        }
    }

    fun label(kind: String) = when (kind) {
        LENT -> "You lent"
        BORROWED -> "You borrowed"
        GOT_BACK -> "Paid you back"
        else -> "You paid back"
    }
}

object Source {
    const val SCREEN = "SCREEN"
    const val SMS = "SMS"
    const val NOTIFICATION = "NOTIFICATION"
    const val MANUAL = "MANUAL"

    fun label(s: String) = when (s) {
        SCREEN -> "Bubble"
        SMS -> "SMS"
        NOTIFICATION -> "Notification"
        else -> "Manual"
    }
}
