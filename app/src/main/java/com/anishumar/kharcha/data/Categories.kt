package com.anishumar.kharcha.data

import android.content.Context

object Categories {

    val all = listOf(
        "Food", "Groceries", "Travel", "Fuel", "Shopping", "Bills",
        "Rent", "Entertainment", "Health", "Education", "Transfers", "Other"
    )

    /** Money that left your account but isn't spending: lent out, or paying back a loan. */
    const val LENT = "Lent"
    const val REPAID = "Repaid"
    fun isLoan(category: String) = category == LENT || category == REPAID

    private val keywords: List<Pair<String, List<String>>> = listOf(
        "Food" to listOf(
            "swiggy", "zomato", "eatsure", "domino", "pizza", "mcdonald", "kfc", "burger",
            "starbucks", "cafe", "coffee", "chai", " tea", "restaurant", "hotel", "biryani",
            "bakery", "sweets", "dhaba", "kitchen", "food", "eats", "box8", "faasos",
            "haldiram", "subway", "barbeque", "canteen", "juice", "thindi", "darshini"
        ),
        "Groceries" to listOf(
            "blinkit", "zepto", "instama", "bigbasket", "dmart", "jiomart", "grofers",
            "kirana", "supermarket", "mart", "fresh", "milk", "dairy", "nandini",
            "more retail", "ratnadeep", "spencer", "vegetable", "fruits", "country delight"
        ),
        "Travel" to listOf(
            "uber", " ola", "rapido", "namma yatri", "irctc", "railway", "metro", "bmrcl",
            "redbus", "makemyt", "ibibo", "cleartrip", "indigo", "air india", "akasa",
            "vistara", "ixigo", " auto", " cab", "taxi", " bus", "fastag", "toll", "parking", "yulu"
        ),
        "Fuel" to listOf(
            "petrol", "fuel", "indian oil", "iocl", "bharat petroleum", "bpcl", "hpcl",
            "hindustan petroleum", "shell", "nayara", "filling station"
        ),
        "Shopping" to listOf(
            "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma",
            "reliance digital", "decathlon", "ikea", "lifestyle", "westside", "zudio",
            "trends", "h&m", "uniqlo", "lenskart", "store", "retail", "fashion"
        ),
        "Bills" to listOf(
            "electricity", "bescom", "bwssb", "water", "recharge", "jio", "airtel", "vodafone",
            " vi ", "bsnl", "act fibernet", "broadband", "wifi", "dth", "tata play", " gas",
            "indane", "bharatgas", "insurance", " lic", " emi", "bill", "credit card", " cred",
            "anthropic", "claude", "openai", "chatgpt", "github", "google play", "google cloud", "icloud"
        ),
        "Rent" to listOf(" rent", "nobroker", "housing", "pg ", "hostel", "maintenance", "society"),
        "Entertainment" to listOf(
            "netflix", "hotstar", "prime video", "spotify", "youtube", "bookmyshow", "pvr",
            "inox", "cinema", "movie", "steam", "playstation", "district", "gaana", "sonyliv", "zee5"
        ),
        "Health" to listOf(
            "pharmacy", "medical", "medplus", "apollo", "1mg", "pharmeasy", "netmeds",
            "hospital", "clinic", "diagnostic", " lab", "doctor", "practo", " cult", "gym", "fitness"
        ),
        "Education" to listOf(
            "udemy", "coursera", "byju", "unacademy", "school", "college", "university",
            "tuition", "course", "books", "leetcode"
        )
    )

    /** Normalised key for learned payee → category rules. */
    fun key(payee: String): String =
        payee.lowercase().substringBefore("@").replace(Regex("[^a-z0-9]"), "").take(40)

    fun guess(context: Context, payee: String): String {
        ExpenseDb.get(context).ruleFor(key(payee))?.let { return it }
        val p = " ${payee.lowercase()} "
        for ((cat, words) in keywords) {
            if (words.any { p.contains(it) }) return cat
        }
        // Looks like a person: 2-3 plain words, or a phone-number UPI id
        val looksLikePhoneVpa = Regex("^\\d{10}@").containsMatchIn(payee.trim())
        val words = payee.trim().split(Regex("\\s+"))
        val looksLikeName = words.size in 2..3 && words.all { it.matches(Regex("[A-Za-z.]+")) }
        if (looksLikePhoneVpa || looksLikeName) return "Transfers"
        return "Other"
    }
}
