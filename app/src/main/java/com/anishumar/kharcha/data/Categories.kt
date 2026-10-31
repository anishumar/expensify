package com.anishumar.kharcha.data

import android.content.Context

object Categories {

    val all = listOf(
        "Food", "Groceries", "Travel", "Fuel", "Shopping", "Bills",
        "Rent", "Entertainment", "Health", "Education", "Transfers", "Other"
    )

    val emoji = mapOf(
        "Food" to "🍔", "Groceries" to "🛒", "Travel" to "🚕", "Fuel" to "⛽",
        "Shopping" to "🛍️", "Bills" to "💡", "Rent" to "🏠", "Entertainment" to "🎬",
        "Health" to "💊", "Education" to "📚", "Transfers" to "👤", "Other" to "📦"
    )

    val colors = mapOf(
        "Food" to 0xFFF97316.toInt(), "Groceries" to 0xFF22C55E.toInt(),
        "Travel" to 0xFF3B82F6.toInt(), "Fuel" to 0xFFEAB308.toInt(),
        "Shopping" to 0xFFEC4899.toInt(), "Bills" to 0xFF8B5CF6.toInt(),
        "Rent" to 0xFF14B8A6.toInt(), "Entertainment" to 0xFFEF4444.toInt(),
        "Health" to 0xFF06B6D4.toInt(), "Education" to 0xFF6366F1.toInt(),
        "Transfers" to 0xFF64748B.toInt(), "Other" to 0xFF9CA3AF.toInt()
    )

    private val keywords: List<Pair<String, List<String>>> = listOf(
        "Food" to listOf(
            "swiggy", "zomato", "eatsure", "domino", "pizza", "mcdonald", "kfc", "burger",
            "starbucks", "cafe", "coffee", "chai", " tea", "restaurant", "hotel", "biryani",
            "bakery", "sweets", "dhaba", "kitchen", "food", "eats", "box8", "faasos",
            "haldiram", "subway", "barbeque", "canteen", "juice", "thindi", "darshini"
        ),
        "Groceries" to listOf(
            "blinkit", "zepto", "instamart", "bigbasket", "dmart", "jiomart", "grofers",
            "kirana", "supermarket", "mart", "fresh", "milk", "dairy", "nandini",
            "more retail", "ratnadeep", "spencer", "vegetable", "fruits", "country delight"
        ),
        "Travel" to listOf(
            "uber", " ola", "rapido", "namma yatri", "irctc", "railway", "metro", "bmrcl",
            "redbus", "makemytrip", "goibibo", "cleartrip", "indigo", "air india", "akasa",
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
            "indane", "bharatgas", "insurance", " lic", " emi", "bill", "credit card", " cred"
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
