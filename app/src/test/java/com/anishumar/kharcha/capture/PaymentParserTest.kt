package com.anishumar.kharcha.capture

import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentParserTest {

    private fun spend(sms: String, amount: Double, payee: String? = null) {
        val p = PaymentParser.parse(sms)
        assertNotNull("not parsed: $sms", p)
        assertEquals(amount, p!!.amount, 0.001)
        if (payee != null) assertEquals(payee, p.payee)
    }

    private fun ignored(sms: String) = assertNull(PaymentParser.parse(sms))

    @Test fun creditCardSpends() {
        spend("Thank you for using your HDFC Bank Credit Card ending 1234 for Rs 1,499.00 at AMAZON on 2026-10-30:12:34:56. Authorization code:- 123456", 1499.0, "Amazon")
        spend("Spent Rs.2350 On HDFC Bank Card 1234 At SWIGGY On 2026-10-30:20:11:05 Bal Rs.45000 Not You? Call 18002586161/SMS BLOCK DC 1234 to 7308080808", 2350.0, "Swiggy")
        spend("Your ICICI Bank Credit Card XX1234 has been used for a transaction of INR 1,234.00 on Oct 30, 2026 at 12:34:56. Info: FLIPKART. The Available Credit Limit on your card is INR 98,766.00", 1234.0, "Flipkart")
        spend("INR 640.00 spent using ICICI Bank Card XX1234 on 30-Oct-26 on ZOMATO. Avl Limit: INR 59,360.00. If not you, call 1800 2662/SMS BLOCK 1234 to 9215676766.", 640.0, "Zomato")
        spend("Rs.899.00 spent on your SBI Credit Card ending with 1234 at MYNTRA on 30/10/26. Trxn. not done by you? Report at https://sbicard.com/Dispute", 899.0, "Myntra")
        spend("INR 450 spent on credit card no. XX1234 on 30-10-26 12:00:00 at UBER INDIA . Avl Lmt INR 1,00,000. SMS BLOCK 1234 to 919951860002, if not you - Axis Bank", 450.0, "Uber India")
        spend("Your IDFC FIRST Bank Credit Card XX1234 has been used for INR 300.00 at BLINKIT on 30-OCT-2026.", 300.0, "Blinkit")
        spend("Alert: You've spent INR 1,234.00 on your AMEX card ** 12345 at NETFLIX on 30 October 2026 at 12:34 PM IST.", 1234.0, "Netflix")
        spend("INR 199 charged on your AU Credit Card XX1234 at SPOTIFY on 30-10-2026", 199.0, "Spotify")
        spend("Rs.120.00 debited from your RuPay Credit Card XX1234 to VPA chai.point@ybl on 30-10-26. UPI Ref 430312345678", 120.0)
    }

    @Test fun cardGatewayPayees() {
        spend("Spent INR 1136.45 Axis Bank Card no. XX0622 22-08-26 10:29:18 IST RAZ*Makemyt Avl Limit: INR 2288.99 Not you? SMS BLOCK 0622 to 919951860002", 1136.45, "Makemyt")
        spend("Spent INR 309  using Axis Bank Credit Card no. XX0622 on 24-10-26 02:19:22 IST at  PAY*SWIGGY.  Avl limit: INR 74.11. Not you? SMS BLOCK 0622 to +919951860002", 309.0, "Swiggy")
        spend("Transaction Successful! INR 11999.00 spent on your IDFC FIRST Bank Credit Card ending XX5644 at ANTHROPIC* CLAUDE SUB on 29 OCT 2026 at 05:31 PM Avbl Limit: INR 1002", 11999.0, "Anthropic Claude Sub")
        spend("INR 11999.00 for Anthropic paid from your IDFC FIRST Bank Credit Card XX5644 on 29/10/2026 basis standing instruction (SI).", 11999.0, "Anthropic")
        spend("Rs 2000 withdrawn@ WASSEYPU on 12OCT26 19:29 Bal Rs 0 Ref 628519014386. Not you? Call 18004251199/ SMS NO 8220 to 9895088888 -Federal Bank", 2000.0, "Wasseypu")
    }

    @Test fun remindersAndAdsIgnored() {
        ignored("Your Axis Bank Credit Card no. XX0622 has an overdue. Pay the min due of INR 157.00 at https://ccm.axis.bank.in")
        ignored("Pay Total Amount Due of Rs 15,891.46 or Minimum Amount Due of Rs 15,891.46 by 30-Sep-26 towards ICICI Bank Credit Card XX3003. Delay/Non-payment is reported to Credit Bureaus")
        ignored("Get a personal loan of Rs. 18,000 on Meesho sent directly to your bank account.")
        ignored("UNITED FOODBRANDS LIMITED has requested payment of INR 50.00. You can pay through this link: https://rzp.io/x")
        ignored("Dear UPI User, UPI AutoPay for Google Play debit of Rs.89.00 is scheduled on .23/09/26")
        ignored("INR 11999.00 for Anthropic will be deducted from IDFC FIRST Bank Credit Card XX5644 on 29/10/2026 basis standing instruction (SI).")
        ignored("Thank you for payment of INR 12,494.57 towards your FIRST Millennia Credit Card XX5644 on 02 Oct 2026. IDFC FIRST Bank")
    }

    @Test fun detailsForTransactionPage() {
        val axis = "Spent INR 309  using Axis Bank Credit Card no. XX0622 on 24-10-26 02:19:22 IST at  PAY*SWIGGY.  Avl limit: INR 74.11."
        assertEquals("Axis Bank Credit Card ••0622", PaymentParser.account(axis))
        assertEquals("Credit card", PaymentParser.method(axis))
        val sbi = "Dear UPI user A/C X2209 debited by 45.00 on date 28Oct26 trf to supriya Refno 630195424012 If not u? call-1800111109 -SBI"
        assertEquals("SBI A/c ••2209", PaymentParser.account(sbi))
        assertEquals("UPI", PaymentParser.method(sbi))
        val idfc = "INR 11999.00 for Anthropic paid from your IDFC FIRST Bank Credit Card XX5644 on 29/10/2026 basis standing instruction (SI)."
        assertEquals("IDFC FIRST Bank Credit Card ••5644", PaymentParser.account(idfc))
        assertEquals("Credit card · AutoPay", PaymentParser.method(idfc))
        assertEquals("ATM withdrawal", PaymentParser.method("Rs 2000 withdrawn@ WASSEYPU on 12OCT26 19:29 Bal Rs 0 -Federal Bank"))
        assertEquals("chai.point@ybl", PaymentParser.upiId("Rs.120.00 debited to VPA chai.point@ybl on 30-10-26"))
        assertNull(PaymentParser.upiId("Statement is sent to an********86@gmail.com."))
    }

    // ---- bubble on app screens ----

    /** 31 Oct 2026, 14:00 local time. */
    private val now = java.util.Calendar.getInstance().apply { set(2026, 9, 31, 14, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.util.Calendar.getInstance().apply { set(y, mo - 1, d, h, mi, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun screenTime() {
        assertEquals(at(2026, 10, 31, 11, 48), PaymentParser.timestamp("Paid to\nSwiggy\n₹202\nCompleted\n31 Oct 2026, 11:48 am\nUPI transaction ID\n430312345678", now))
        assertEquals(at(2026, 10, 30, 21, 5), PaymentParser.timestamp("Paid Successfully\n₹85\n9:05 pm on 30 Oct 2026", now))
        assertEquals(at(2026, 10, 29, 23, 39), PaymentParser.timestamp("Order placed on 29 October 2026 at 11:39 PM\nGrand Total ₹270", now))
        assertEquals(at(2026, 10, 31, 2, 19), PaymentParser.timestamp("Placed on Sat, 31 Oct'26, 2:19 AM", now))
        assertEquals(at(2026, 10, 30, 19, 30), PaymentParser.timestamp("Yesterday, 7:30 pm\nPaid ₹120", now))
        assertEquals(at(2026, 10, 28, 16, 24), PaymentParser.timestamp("October 28, 2026 4:24 PM", now))
        assertEquals(at(2026, 10, 25, 9, 15), PaymentParser.timestamp("Txn date 25/10/2026 09:15", now))
        // only a time, later than now → yesterday
        assertEquals(at(2026, 10, 30, 22, 10), PaymentParser.timestamp("Delivered\n10:10 pm\nTo pay ₹300", now))
        // no year, would be in the future → last year
        assertEquals(at(2025, 12, 24, 20, 0), PaymentParser.timestamp("24 Dec, 8:00 pm", now))
        assertNull(PaymentParser.timestamp("Paid to Swiggy ₹202", now))
    }

    @Test fun checkoutTotals() {
        val swiggy = "Order #123\nPaneer Tikka\n₹249\nGarlic Naan\n₹60\nItem Total\n₹309\nDelivery Fee\n₹35\nTotal savings ₹80\nTo Pay\n₹344\nPaid via HDFC Credit Card"
        assertEquals(344.0, PaymentParser.parse(swiggy, lenient = true)!!.amount, 0.001)
        val zomato = "Order placed on 29 October 2026 at 11:39 PM\nChicken Biryani ₹299\nGrand Total ₹375.50\nYou saved ₹40"
        assertEquals(375.5, PaymentParser.parse(zomato, lenient = true)!!.amount, 0.001)
        val blinkit = "Order summary\nMilk ₹33\nBread ₹45\nBill details\nItem total ₹78\nHandling charge ₹4\nBill total ₹82"
        assertEquals(82.0, PaymentParser.parse(blinkit, lenient = true)!!.amount, 0.001)
        val gpay = "Paid to\nSwiggy\n₹202\nCompleted\n31 Oct 2026, 11:48 am"
        assertEquals(202.0, PaymentParser.parse(gpay, lenient = true)!!.amount, 0.001)
    }

    @Test fun textReadFromScreenshot() {
        // rows as ScreenOcr builds them: label and value side by side, ₹ often misread
        val swiggy = PaymentParser.normalizeOcr(
            "Order delivered\nPaneer Tikka   R249\nItem Total   R309\nDelivery Fee   Z35\nTo Pay   R344\nPaid via card ending 4321\n31 Oct 2026, 1:07 pm"
        )
        val p = PaymentParser.parse(swiggy, lenient = true)!!
        assertEquals(344.0, p.amount, 0.001)
        assertTrue(PaymentParser.hasLabelledTotal(swiggy))
        assertEquals(at(2026, 10, 31, 13, 7), PaymentParser.timestamp(swiggy, now))
        // rupee sign lost entirely
        assertEquals(589.0, PaymentParser.parse("Bill details\nItem total   560\nGrand Total   589", lenient = true)!!.amount, 0.001)
        // item prices alone are not a total
        assertFalse(PaymentParser.hasLabelledTotal("Paneer Tikka ₹249\nGarlic Naan ₹60"))
    }

    @Test fun cardBillPaymentsAreNotExpenses() {
        ignored("Rs 15000.00 debited from A/c XX5678 towards HDFC Bank Credit Card XX1234 on 30-10-26. UPI Ref 430399999999")
        ignored("Payment of Rs 15,000 to ICICI Bank Credit Card XX1234 successful")
        ignored("We have received payment of Rs.15,000.00 towards your HDFC Bank Credit Card XX1234. Thank you.")
        ignored("Your HDFC Bank Credit Card XX1234 statement: Total amount due Rs 15,000. Minimum due Rs 750 is due on 15-Nov-26.")
    }

    @Test fun nonSpendsIgnored() {
        ignored("Refund of Rs 499 has been credited to your credit card XX1234")
        ignored("OTP for transaction of INR 500 at AMAZON on your card XX1234 is 123456")
        ignored("Transaction of Rs 500 at AMAZON on your card XX1234 was declined")
    }

    @Test fun upiSms() {
        spend("Dear UPI user A/C X1234 debited by 250.0 on date 30Oct26 trf to SWIGGY Refno 430312345678. If not u? call 1800111109. -SBI", 250.0, "Swiggy")
        spend("Sent Rs.80.00\nFrom HDFC Bank A/C *1234\nTo RAHUL KUMAR\nOn 30/10/26\nRef 430312345679\nNot You?", 80.0, "Rahul Kumar")
    }

    // ---- bubble vs SMS duplicates ----

    private val t = 1_790_000_000_000L
    private val bubble = Expense(
        id = 1, amount = 120.0, payee = "Swiggy", category = "Food", source = Source.SCREEN,
        ref = "430312345678", timestamp = t,
        raw = "Paid to\nSwiggy\n₹120\nUPI transaction ID\n430312345678\nGoogle transaction ID\nCICAgOCoxyz123"
    )
    private val cardSms = Expense(
        amount = 120.0, payee = "Unknown", category = "Other", source = Source.SMS,
        ref = "123456", timestamp = t + 8 * 60_000, raw = "INR 120 spent ... Ref No 123456"
    )

    @Test fun bubbleAndSmsMerge() {
        assertTrue(PaymentMatcher.isSamePayment(bubble, cardSms))
        assertTrue(PaymentMatcher.isSamePayment(bubble, cardSms.copy(payee = "Swiggy Bangalore")))
        assertTrue(PaymentMatcher.isSamePayment(bubble, cardSms.copy(ref = null, payee = "9876543210@ybl", raw = "Rs 120 debited ... 430312345678")))
    }

    @Test fun separatePaymentsStaySeparate() {
        assertFalse(PaymentMatcher.isSamePayment(bubble, cardSms.copy(ref = "430399999999", payee = "Swiggy")))
        assertFalse(PaymentMatcher.isSamePayment(bubble, cardSms.copy(timestamp = t + 20 * 60_000)))
        assertFalse(PaymentMatcher.isSamePayment(bubble, cardSms.copy(amount = 121.0)))
        val sms1 = cardSms.copy(amount = 20.0, payee = "Chai Point", raw = "Rs 20 spent at CHAI POINT on 30-10 12:01")
        assertFalse(PaymentMatcher.isSamePayment(sms1, sms1.copy(raw = "Rs 20 spent at CHAI POINT on 30-10 12:02")))
        assertTrue(PaymentMatcher.isSamePayment(sms1, sms1.copy(timestamp = sms1.timestamp + 2000)))
    }

    @Test fun twoBankSmsForOneCharge() {
        val spent = Expense(amount = 11999.0, payee = "Anthropic Claude Sub", category = "Other", source = Source.SMS, timestamp = t,
            raw = "Transaction Successful! INR 11999.00 spent on your IDFC FIRST Bank Credit Card ending XX5644 at ANTHROPIC* CLAUDE SUB")
        val si = spent.copy(payee = "Anthropic", timestamp = t + 131_000,
            raw = "INR 11999.00 for Anthropic paid from your IDFC FIRST Bank Credit Card XX5644 on 29/10/2026 basis standing instruction (SI).")
        assertTrue(PaymentMatcher.isSamePayment(spent, si))
    }
}
