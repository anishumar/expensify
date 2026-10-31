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
)

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
