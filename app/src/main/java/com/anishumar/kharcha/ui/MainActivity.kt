package com.anishumar.kharcha.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.anishumar.kharcha.R
import com.anishumar.kharcha.capture.CaptureManager
import com.anishumar.kharcha.capture.PaymentParser
import com.anishumar.kharcha.capture.ScreenReaderService
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.Ledger
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.report.Period
import com.anishumar.kharcha.report.PeriodType
import com.anishumar.kharcha.report.Reports
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERIOD = "period"
        const val EXTRA_START = "start"

        // launcher shortcuts (long-press the app icon), see res/xml/shortcuts.xml
        const val ACTION_ADD_EXPENSE = "com.anishumar.kharcha.ADD_EXPENSE"
        const val ACTION_ADD_LENT = "com.anishumar.kharcha.ADD_LENT"
        const val ACTION_ADD_BORROWED = "com.anishumar.kharcha.ADD_BORROWED"
        private const val KEY_REPAIR = "repair_version"
        private const val REPAIR_VERSION = 2
    }

    private lateinit var period: Period
    private val db by lazy { ExpenseDb.get(this) }
    private val prefs by lazy { getSharedPreferences(ScreenReaderService.PREFS, MODE_PRIVATE) }
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val type = intent.getStringExtra(EXTRA_PERIOD)?.let { PeriodType.valueOf(it) } ?: PeriodType.WEEK
        period = Period.of(type, intent.getLongExtra(EXTRA_START, System.currentTimeMillis()))

        findViewById<View>(R.id.fab).apply {
            Ui.pressable(this, 0.92f)
            setOnClickListener { addSheet() }
        }
        findViewById<View>(R.id.menu).apply {
            Ui.pressable(this, 0.9f)
            setOnClickListener { Ui.push(this@MainActivity, Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        listOf(R.id.btnWeek, R.id.btnMonth).forEach { Ui.pressable(findViewById(it), 0.96f) }

        // The date line scrolls away; the bar's small date and edge line track the scroll 1:1
        val small = findViewById<TextView>(R.id.smallTitle)
        val edge = findViewById<View>(R.id.topEdge)
        findViewById<NestedScrollView>(R.id.scroll).setOnScrollChangeListener { _: NestedScrollView, _, y, _, _ ->
            small.alpha = ((y - dp(8)) / dp(24).toFloat()).coerceIn(0f, 1f)
            edge.alpha = (y / dp(16).toFloat()).coerceIn(0f, 1f)
        }
        findViewById<View>(R.id.btnWeek).setOnClickListener { if (period.type != PeriodType.WEEK) { period = Period.of(PeriodType.WEEK); render() } }
        findViewById<View>(R.id.btnMonth).setOnClickListener { if (period.type != PeriodType.MONTH) { period = Period.of(PeriodType.MONTH); render() } }
        findViewById<View>(R.id.prev).setOnClickListener { step(-1) }
        findViewById<View>(R.id.next).setOnClickListener { step(1) }
        swipeBetweenPeriods(findViewById(R.id.hero))
        repairOnce()
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShortcut(i: Intent?) {
        when (i?.action) {
            ACTION_ADD_EXPENSE -> Ui.push(this, Intent(this, EditExpenseActivity::class.java))
            ACTION_ADD_LENT -> LoanDialog.show(this, kind = Ledger.LENT) { render() }
            ACTION_ADD_BORROWED -> LoanDialog.show(this, kind = Ledger.BORROWED) { render() }
            else -> return
        }
        i.action = Intent.ACTION_MAIN   // don't fire again on recreation
    }

    override fun onResume() {
        super.onResume()
        // red dot on the hamburger until every permission is on
        findViewById<View>(R.id.menuDot).visibility = if (SettingsActivity.setupComplete(this)) View.GONE else View.VISIBLE
        render()
    }

    /** Re-checks rows saved by older versions against the current parser and dedup rules. */
    private fun repairOnce() {
        if (prefs.getInt(KEY_REPAIR, 0) >= REPAIR_VERSION) return
        Thread {
            val n = runCatching { CaptureManager.repair(applicationContext) }.getOrNull() ?: return@Thread
            prefs.edit().putInt(KEY_REPAIR, REPAIR_VERSION).apply()
            if (n > 0) runOnUiThread { toast("Cleaned up $n entries"); render() }
        }.start()
    }

    /** + offers the three things you can add by hand. */
    private fun addSheet() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Expense", "Money I lent", "Money I borrowed")) { _, i ->
                when (i) {
                    0 -> Ui.push(this, Intent(this, EditExpenseActivity::class.java))
                    1 -> LoanDialog.show(this, kind = Ledger.LENT) { render() }
                    else -> LoanDialog.show(this, kind = Ledger.BORROWED) { render() }
                }
            }
            .show()
    }

    // ---------------- report ----------------

    private var dotSpring: SpringAnimation? = null
    private var dotPlaced = false

    /** Week / Month as words: the chosen one is black, the other faint, a red dot springs under the chosen one. */
    private fun renderTabs() {
        val week = period.type == PeriodType.WEEK
        val on = findViewById<TextView>(if (week) R.id.btnWeek else R.id.btnMonth)
        findViewById<TextView>(R.id.btnWeek).setTextColor(if (week) Ui.INK else Ui.FAINT)
        findViewById<TextView>(R.id.btnMonth).setTextColor(if (week) Ui.FAINT else Ui.INK)
        val dot = findViewById<View>(R.id.tabDot)
        on.post {
            val target = on.left + on.width / 2f - dot.width / 2f
            if (!dotPlaced || Ui.reducedMotion(this)) { dot.translationX = target; dotPlaced = true; return@post }
            val spring = dotSpring ?: SpringAnimation(dot, DynamicAnimation.TRANSLATION_X).apply {
                spring = SpringForce().setDampingRatio(0.8f).setStiffness(stiffness(0.35f))
                dotSpring = this
            }
            spring.animateToFinalPosition(target)   // re-targets from the live position if mid-flight
        }
    }

    /** Move a week / month; the numbers leave toward one side and the new ones come in from the other. */
    private fun step(by: Int) {
        if (by > 0 && period.start > System.currentTimeMillis()) return
        val hero = findViewById<View>(R.id.hero)
        period = period.shift(by)
        if (Ui.reducedMotion(this)) { render(); return }
        val d = dp(28).toFloat() * by
        hero.animate().translationX(-d).alpha(0f).setDuration(120).setInterpolator(Ui.EASE_OUT).withEndAction {
            render()
            hero.translationX = d
            hero.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Ui.EASE_OUT).start()
        }.start()
    }

    /** Horizontal fling on the total / chart = previous or next period. Vertical drags still scroll the page. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun swipeBetweenPeriods(target: View) {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) < 600 || kotlin.math.abs(vx) < kotlin.math.abs(vy) * 1.5f) return false
                step(if (vx < 0) 1 else -1)
                return true
            }
        })
        target.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    // ---------------- today ----------------

    private fun renderToday() {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val list = db.between(start, start + 36 * 3_600_000L).filter { it.timestamp <= now + 60_000 }
        val spent = list.filter { it.isSpending }.sumOf { it.amount }
        val date = SimpleDateFormat("EEEE, d MMMM", Locale("en", "IN")).format(now)
        findViewById<TextView>(R.id.dateLabel).text = date.uppercase()
        findViewById<TextView>(R.id.smallTitle).text = SimpleDateFormat("EEE, d MMM", Locale("en", "IN")).format(now)

        val box = findViewById<LinearLayout>(R.id.today)
        box.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(Ui.text(this, "Today", 34f, Ui.semibold(this)), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.text(this, Money.format(spent), 34f, Ui.light(this)))
        box.addView(head)
        box.addView(Ui.text(this,
            when (list.size) { 0 -> "Nothing spent yet."; 1 -> "1 payment"; else -> "${list.size} payments" },
            13f, Ui.regular(this), Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        if (list.isEmpty()) return

        // today's payments as cards you swipe through, bleeding to the screen edges
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        list.forEachIndexed { i, e -> row.addView(todayCard(e), LinearLayout.LayoutParams(dp(148), -2).apply { if (i > 0) marginStart = dp(10) }) }
        box.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); marginStart = -dp(24); marginEnd = -dp(24) })
    }

    private fun todayCard(e: Expense) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.pill(this@MainActivity, Ui.FILL, 20)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(Ui.iconBadge(this@MainActivity, Ui.categoryIcon(e.category), 34, inverted = true))
        addView(Ui.text(this@MainActivity, Money.format(e.amount), 20f, Ui.medium(this@MainActivity)).apply { setPadding(0, dp(14), 0, 0) })
        addView(Ui.text(this@MainActivity, e.payee, 13f, Ui.regular(this@MainActivity)).apply {
            setPadding(0, dp(4), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        addView(Ui.text(this@MainActivity,
            SimpleDateFormat("h:mm a", Locale("en", "IN")).format(e.timestamp).lowercase(),
            12f, Ui.regular(this@MainActivity), Ui.MUTED).apply { setPadding(0, dp(3), 0, 0) })
        Ui.pressable(this, 0.96f)
        setOnClickListener {
            Ui.push(this@MainActivity, Intent(this@MainActivity, TransactionActivity::class.java).putExtra(TransactionActivity.EXTRA_ID, e.id))
        }
    }

    /** Apple's "response" (seconds) → spring stiffness. */
    private fun stiffness(response: Float) = ((2 * Math.PI / response) * (2 * Math.PI / response)).toFloat()

    private fun render() {
        renderToday()
        renderTabs()
        findViewById<TextView>(R.id.periodLabel).text = period.label()
        val now = System.currentTimeMillis()
        val isCurrent = now in period.start until period.end
        findViewById<View>(R.id.next).alpha = if (isCurrent || period.start > now) 0.2f else 0.5f

        val list = db.between(period.start, period.end)          // everything, for Activity
        val s = Reports.summarize(period, list.filter { it.isSpending })
        val prevTotal = period.shift(-1).let { p -> db.spending(p.start, p.end).sumOf { it.amount } }

        findViewById<TextView>(R.id.total).text = Money.format(s.total)
        val change = when {
            prevTotal <= 0 -> ""
            else -> {
                val pct = ((s.total - prevTotal) / prevTotal * 100).toInt()
                val word = if (period.type == PeriodType.WEEK) "last week" else "last month"
                if (pct >= 0) "   ↑ $pct% vs $word" else "   ↓ ${-pct}% vs $word"
            }
        }
        val avgDays = if (isCurrent) ((now - period.start) / 86_400_000L + 1).toInt() else period.days()
        val avg = if (avgDays > 0) s.total / avgDays else 0.0
        findViewById<TextView>(R.id.subtitle).text =
            "${s.count} payments   ${Money.format(avg)} a day$change"

        val todayIdx = if (isCurrent) {
            if (period.type == PeriodType.MONTH) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) - 1
            else ((now - period.start) / 86_400_000L).toInt()
        } else -1
        findViewById<BarChartView>(R.id.chart).set(s.daily, s.dayLabels, todayIdx)
        renderLoans()

        val cats = findViewById<LinearLayout>(R.id.categories)
        cats.removeAllViews()
        if (s.byCategory.isEmpty()) cats.addView(empty("Nothing spent yet."))
        for ((cat, amt) in s.byCategory) cats.addView(categoryRow(cat, amt, if (s.total > 0) amt / s.total else 0.0))

        val pay = findViewById<LinearLayout>(R.id.payees)
        pay.removeAllViews()
        if (s.topPayees.isEmpty()) pay.addView(empty("—"))
        for ((i, p) in s.topPayees.withIndex()) {
            if (i > 0) pay.addView(Ui.hairline(this))
            pay.addView(payeeRow(i + 1, p.first, p.second))
        }

        val tx = findViewById<LinearLayout>(R.id.transactions)
        tx.removeAllViews()
        if (list.isEmpty()) tx.addView(empty("Payments show up here as they happen. Open ☰ to turn on SMS and notification reading."))
        // Grouped by day, each day with what was spent that day
        for ((i, group) in list.groupBy { dayLabel(it.timestamp) }.entries.withIndex()) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(if (i == 0) 4 else 26), 0, dp(6))
            }
            header.addView(Ui.overline(this, group.key), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(Ui.overline(this, Money.format(group.value.filter { it.isSpending }.sumOf { it.amount })))
            tx.addView(header)
            group.value.forEachIndexed { j, e ->
                if (j > 0) tx.addView(Ui.hairline(this, dp(60)))
                tx.addView(txRow(e))
            }
        }
    }

    /** "You'll get ₹X · You owe ₹Y", opens Lend & borrow. */
    private fun renderLoans() {
        val card = findViewById<LinearLayout>(R.id.loansCard)
        card.removeAllViews()
        val balances = db.people().map { Ledger.balance(it.second) }
        val get = balances.filter { it > 0.009 }.sum()
        val owe = balances.filter { it < -0.009 }.sumOf { -it }
        card.addView(Ui.iconBadge(this, R.drawable.ic_cat_lent, 40, inverted = true).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
        })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(Ui.text(this, "Lend & borrow", 16f, Ui.medium(this)))
        val sub = if (balances.isEmpty()) "Track who owes you, and whom you owe"
            else "You'll get ${Money.format(get)}   ·   You owe ${Money.format(owe)}"
        mid.addView(Ui.text(this, sub, 13f, Ui.regular(this), Ui.MUTED).apply { setPadding(0, dp(5), 0, 0) })
        card.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_forward); alpha = 0.35f
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        if (!card.hasOnClickListeners()) {
            Ui.pressable(card, 0.98f)
            card.setOnClickListener { Ui.push(this, Intent(this, LoansActivity::class.java)) }
            // hold: straight to "I lent / I borrowed", same as + on the Lend & borrow page
            card.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                LoanDialog.show(this) { render() }
                true
            }
        }
    }

    private fun dayLabel(t: Long): String {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            t >= today -> "Today"
            t >= today - 86_400_000L -> "Yesterday"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale("en", "IN")).format(t)
        }
    }

    private fun empty(msg: String) = Ui.text(this, msg, 14f, Ui.regular(this), Ui.MUTED).apply {
        setLineSpacing(0f, 1.3f)
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Icon, category, share and amount, with a hairline bar. */
    private fun categoryRow(cat: String, amount: Double, frac: Double): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(Ui.iconBadge(this, Ui.categoryIcon(cat), 40).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        top.addView(Ui.text(this, cat, 16f, Ui.medium(this)), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.text(this, "${(frac * 100).toInt()}%", 12f, Ui.regular(this), Ui.MUTED).apply {
            setPadding(0, 0, dp(12), dp(2))
        })
        top.addView(Ui.text(this, Money.format(amount), 15f, Ui.medium(this)).apply { setPadding(0, 0, 0, dp(2)) })
        box.addView(top)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = (frac * 1000).toInt().coerceIn(0, 1000)
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.INK)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.HAIRLINE)
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(10) })
        return row
    }

    private fun payeeRow(rank: Int, name: String, amount: Double): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        r.addView(Ui.text(this, "$rank", 15f, Ui.regular(this), Ui.FAINT), LinearLayout.LayoutParams(dp(28), -2))
        r.addView(Ui.text(this, name, 15f, Ui.regular(this)).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Ui.text(this, Money.format(amount), 15f, Ui.medium(this)))
        return r
    }

    private fun txRow(e: Expense): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            Ui.pressable(this, 0.98f)
            setOnClickListener {
                Ui.push(this@MainActivity, Intent(this@MainActivity, TransactionActivity::class.java)
                    .putExtra(TransactionActivity.EXTRA_ID, e.id))
            }
        }
        r.addView(Ui.iconBadge(this, Ui.categoryIcon(e.category), 44).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16)
        })

        // Like Google Pay: who, what and when, and the account it came from
        val time = SimpleDateFormat("h:mm a", Locale("en", "IN")).format(e.timestamp).lowercase()
        val account = e.raw?.let { PaymentParser.account(it) }
        val method = e.raw?.let { PaymentParser.method(it) }
        val status = when (e.category) {
            Categories.LENT -> "Lent"
            Categories.REPAID -> "Paid back"
            else -> "Paid"
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        line1.addView(Ui.text(this, e.payee, 16f, Ui.medium(this)).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line1.addView(Ui.text(this, Money.format(e.amount), 16f, Ui.medium(this)).apply { setPadding(dp(12), 0, 0, 0) })
        mid.addView(line1)
        val line2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }
        line2.addView(Ui.text(this, "${e.category}  ·  $time", 13f, Ui.regular(this), Ui.MUTED).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line2.addView(Ui.text(this, status, 13f, Ui.regular(this), if (Categories.isLoan(e.category)) Ui.INK else Ui.MUTED).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        mid.addView(line2)
        // "Axis Bank Credit Card ••0622" already says it's a card, so don't repeat "Credit card"
        val saysCard = account?.contains("Card") == true && method?.substringBefore(" · ")?.endsWith("card") == true
        val methodText = if (saysCard) method?.substringAfter(" · ", "")?.ifEmpty { null } else method
        val from = listOfNotNull(account, methodText)
        if (from.isNotEmpty()) mid.addView(Ui.text(this, from.joinToString("  ·  "), 12f, Ui.regular(this), Ui.FAINT).apply {
            setPadding(0, dp(4), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
