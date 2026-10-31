package com.anishumar.kharcha.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anishumar.kharcha.R
import com.anishumar.kharcha.capture.ScreenReaderService
import com.anishumar.kharcha.capture.SmsImporter
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.data.Source
import com.anishumar.kharcha.report.Period
import com.anishumar.kharcha.report.PeriodType
import com.anishumar.kharcha.report.Reports
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERIOD = "period"
        const val EXTRA_START = "start"
        private const val REQ_SMS = 11
        private const val REQ_NOTIF = 12
    }

    private lateinit var period: Period
    private val db by lazy { ExpenseDb.get(this) }
    private val prefs by lazy { getSharedPreferences(ScreenReaderService.PREFS, MODE_PRIVATE) }
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val type = intent.getStringExtra(EXTRA_PERIOD)?.let { PeriodType.valueOf(it) } ?: PeriodType.WEEK
        period = Period.of(type, intent.getLongExtra(EXTRA_START, System.currentTimeMillis()))

        setupToolbar()

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.periodToggle)
        toggle.check(if (type == PeriodType.WEEK) R.id.btnWeek else R.id.btnMonth)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            period = Period.of(if (id == R.id.btnWeek) PeriodType.WEEK else PeriodType.MONTH)
            render()
        }
        findViewById<View>(R.id.prev).setOnClickListener { period = period.shift(-1); render() }
        findViewById<View>(R.id.next).setOnClickListener { period = period.shift(1); render() }
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
        render()
    }

    // ---------------- toolbar ----------------

    private fun setupToolbar() {
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        val m = tb.menu
        m.add(0, 1, 0, "Add").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 2, 1, "Export CSV")
        m.add(0, 3, 2, "Import past SMS")
        m.add(0, 4, 3, "Show/hide bubble")
        tb.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(this, EditExpenseActivity::class.java))
                2 -> exportCsv()
                3 -> importSms()
                4 -> {
                    val on = !prefs.getBoolean(ScreenReaderService.KEY_BUBBLE, true)
                    prefs.edit().putBoolean(ScreenReaderService.KEY_BUBBLE, on).apply()
                    ScreenReaderService.refresh(this)
                    toast(if (on) "Bubble on" else "Bubble hidden")
                }
            }
            true
        }
    }

    // ---------------- setup checklist ----------------

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ScreenReaderService::class.java).flattenToString()
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(me, ignoreCase = true) }
    }

    private fun notifListenerOn(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun smsOn() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun postNotifOn() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun renderSetup() {
        val card = findViewById<View>(R.id.setupCard)
        val rows = findViewById<LinearLayout>(R.id.setupRows)
        rows.removeAllViews()
        fun row(title: String, desc: String, done: Boolean, action: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = (if (done) "✅ " else "⬜ ") + title
                textSize = 15f; setTextColor(0xFF111827.toInt())
            })
            texts.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(0xFF6B7280.toInt())
            })
            r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (!done) r.addView(Button(this).apply {
                text = "Enable"; isAllCaps = false
                setOnClickListener { action() }
            })
            rows.addView(r)
        }
        row("Payment bubble", "Accessibility → Installed apps → Kharcha payment bubble → On", accessibilityOn()) {
            AlertDialog.Builder(this)
                .setTitle("Turn on the bubble")
                .setMessage(
                    "Android will open Accessibility settings.\n\n" +
                        "Tap \"Installed apps\" (or \"Downloaded apps\") → \"Kharcha payment bubble\" → turn it on.\n\n" +
                        "If Android says it's a \"restricted setting\": go to Settings → Apps → Kharcha → ⋮ (top right) → " +
                        "\"Allow restricted settings\", then try again.\n\n" +
                        "Kharcha only reads the screen when you tap the ₹ bubble, and nothing leaves your phone."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }
        row("UPI app notifications", "Auto-log GPay, PhonePe, Paytm payments", notifListenerOn()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row("Bank SMS", "Auto-log debit SMS from your bank", smsOn()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), REQ_SMS
            )
        }
        row("Reports & alerts", "Weekly/monthly summary notifications", postNotifOn()) {
            if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }
        card.visibility = if (accessibilityOn() && notifListenerOn() && smsOn() && postNotifOn()) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
        if (requestCode == REQ_SMS && smsOn()) {
            AlertDialog.Builder(this)
                .setTitle("Import past payments?")
                .setMessage("Kharcha can scan bank SMS from the last 90 days and add those debits now.")
                .setPositiveButton("Import") { _, _ -> importSms() }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    private fun importSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), REQ_SMS
            )
            return
        }
        toast("Scanning SMS…")
        Thread {
            val n = runCatching { SmsImporter.importInbox(applicationContext) }.getOrDefault(0)
            runOnUiThread { toast("Added $n payments from SMS"); render() }
        }.start()
    }

    // ---------------- report ----------------

    private fun render() {
        findViewById<TextView>(R.id.periodLabel).text = period.label()
        val isCurrent = System.currentTimeMillis() in period.start until period.end
        findViewById<View>(R.id.next).alpha = if (period.start > System.currentTimeMillis()) 0.3f else 1f

        val list = db.between(period.start, period.end)
        val s = Reports.summarize(period, list)
        val prevTotal = period.shift(-1).let { p -> db.between(p.start, p.end).sumOf { it.amount } }

        findViewById<TextView>(R.id.total).text = Money.format(s.total)
        val change = when {
            prevTotal <= 0 -> ""
            else -> {
                val pct = ((s.total - prevTotal) / prevTotal * 100).toInt()
                val word = if (period.type == PeriodType.WEEK) "last week" else "last month"
                if (pct >= 0) "  ·  ▲ $pct% vs $word" else "  ·  ▼ ${-pct}% vs $word"
            }
        }
        val avgDays = if (isCurrent) ((System.currentTimeMillis() - period.start) / 86_400_000L + 1).toInt() else period.days()
        val avg = if (avgDays > 0) s.total / avgDays else 0.0
        findViewById<TextView>(R.id.subtitle).text =
            "${s.count} payments  ·  ${Money.format(avg)}/day$change"

        val todayIdx = if (isCurrent) {
            if (period.type == PeriodType.MONTH)
                java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) - 1
            else ((System.currentTimeMillis() - period.start) / 86_400_000L).toInt()
        } else -1
        findViewById<BarChartView>(R.id.chart).set(s.daily, s.dayLabels, todayIdx)

        // categories
        val cats = findViewById<LinearLayout>(R.id.categories)
        cats.removeAllViews()
        if (s.byCategory.isEmpty()) cats.addView(empty("No spends yet"))
        for ((cat, amt) in s.byCategory) {
            cats.addView(barRow(
                "${Categories.emoji[cat] ?: ""}  $cat", amt, if (s.total > 0) amt / s.total else 0.0,
                Categories.colors[cat] ?: 0xFF9CA3AF.toInt()
            ))
        }

        // payees
        val pay = findViewById<LinearLayout>(R.id.payees)
        pay.removeAllViews()
        if (s.topPayees.isEmpty()) pay.addView(empty("—"))
        val maxP = s.topPayees.firstOrNull()?.second ?: 1.0
        for ((name, amt) in s.topPayees) pay.addView(barRow(name, amt, amt / maxP, 0xFF0F766E.toInt()))

        // transactions
        val tx = findViewById<LinearLayout>(R.id.transactions)
        tx.removeAllViews()
        if (list.isEmpty()) tx.addView(empty("Tap the ₹ bubble on a payment screen, or turn on SMS/notifications above."))
        val df = SimpleDateFormat("EEE d MMM, h:mm a", Locale("en", "IN"))
        for (e in list) tx.addView(txRow(e, df))
    }

    private fun empty(msg: String) = TextView(this).apply {
        text = msg; textSize = 13f; setTextColor(0xFF6B7280.toInt())
        setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
    }

    private fun barRow(label: String, amount: Double, frac: Double, color: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = label; textSize = 14f; setTextColor(0xFF111827.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = Money.format(amount); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt())
        })
        box.addView(top)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = (frac * 1000).toInt().coerceIn(0, 1000)
            progressTintList = android.content.res.ColorStateList.valueOf(color)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE5E7EB.toInt())
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, (6 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
        return box
    }

    private fun txRow(e: Expense, df: SimpleDateFormat): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EditExpenseActivity::class.java)
                    .putExtra(EditExpenseActivity.EXTRA_ID, e.id))
            }
        }
        val icon = TextView(this).apply {
            text = Categories.emoji[e.category] ?: "📦"
            textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(((Categories.colors[e.category] ?: 0xFF9CA3AF.toInt()) and 0x00FFFFFF) or 0x22000000)
            }
        }
        r.addView(icon, LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt()).apply {
            marginEnd = (12 * dp).toInt()
        })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = e.payee; textSize = 15f; setTextColor(0xFF111827.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        mid.addView(TextView(this).apply {
            text = "${df.format(e.timestamp)}  ·  ${e.category}  ·  ${Source.label(e.source)}"
            textSize = 12f; setTextColor(0xFF6B7280.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(this).apply {
            text = "−" + Money.format(e.amount); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt())
        })
        return r
    }

    // ---------------- export ----------------

    private fun exportCsv() {
        val list = db.all()
        if (list.isEmpty()) { toast("Nothing to export yet"); return }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        val csv = buildString {
            append("Date,Amount,Payee,Category,Source,Reference,Note\n")
            for (e in list.sortedBy { it.timestamp }) {
                append(df.format(e.timestamp)).append(',')
                append(String.format(Locale.US, "%.2f", e.amount)).append(',')
                append(q(e.payee)).append(',').append(q(e.category)).append(',')
                append(Source.label(e.source)).append(',').append(q(e.ref)).append(',')
                append(q(e.note)).append('\n')
            }
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "kharcha-expenses.csv").apply { writeText(csv) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Export expenses"
        ))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
