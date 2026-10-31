package com.anishumar.kharcha.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.anishumar.kharcha.R
import com.anishumar.kharcha.capture.CaptureManager
import com.anishumar.kharcha.capture.PaymentParser
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Ledger
import com.anishumar.kharcha.data.LedgerEntry
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.data.Source
import com.anishumar.kharcha.report.Period
import com.anishumar.kharcha.report.PeriodType
import java.text.SimpleDateFormat
import java.util.Locale

/** Receipt-style page for one payment: amount, status, account, refs, payee history, original message. */
class TransactionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private val db by lazy { ExpenseDb.get(this) }
    private fun dp(v: Int) = Ui.dp(this, v)
    private val longDate = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale("en", "IN"))
    private val shortDate = SimpleDateFormat("d MMM yyyy", Locale("en", "IN"))
    private var id = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.edit).background = Ui.pill(this, Ui.INK, 24)
        listOf(R.id.edit, R.id.delete, R.id.share).forEach { Ui.pressable(findViewById(it), 0.96f) }
        id = intent.getLongExtra(EXTRA_ID, -1)
        CaptureManager.cancelNotification(this, id)
    }

    override fun finish() {
        super.finish()
        Ui.popTransition(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val e = db.byId(id) ?: run { finish(); return }
        val raw = e.raw
        val root = findViewById<LinearLayout>(R.id.content)
        root.removeAllViews()
        hero(root, e)

        section(root, "Details")
        val upi = raw?.let { PaymentParser.upiId(it) }?.takeIf { !it.equals(e.payee, ignoreCase = true) }
        val rows = mutableListOf<() -> Unit>()
        rows += { row(root, "Paid to", e.payee, sub = upi) }
        raw?.let { PaymentParser.account(it) }?.let { a -> rows += { row(root, "Paid from", a) } }
        raw?.let { PaymentParser.method(it) }?.let { m -> rows += { row(root, "Method", m) } }
        e.ref?.let { ref ->
            val label = if (Regex("^\\d{12}$").matches(ref)) "UPI transaction ID" else "Reference"
            rows += { row(root, label, ref, action = "Copy") { copy(label, ref) } }
        }
        rows += { row(root, "Category", e.category, action = "Change") { pickCategory(e) } }
        rows += { row(root, "Date", longDate.format(e.timestamp).replace("AM", "am").replace("PM", "pm")) }
        rows += {
            val auto = e.source == Source.SMS || e.source == Source.NOTIFICATION
            row(root, "Logged from", Source.label(e.source) + if (auto) ", automatically" else "")
        }
        e.note?.let { n -> rows += { row(root, "Note", n) } }
        rows.forEachIndexed { i, add -> if (i > 0) root.addView(Ui.hairline(this)); add() }

        loanSection(root, e)
        payeeHistory(root, e)

        if (!raw.isNullOrBlank() && (e.source == Source.SMS || e.source == Source.NOTIFICATION)) {
            section(root, if (e.source == Source.SMS) "Bank message" else "Notification")
            root.addView(Ui.text(this, raw.trim(), 13f, Ui.regular(this), 0xB3000000.toInt()).apply {
                setLineSpacing(0f, 1.35f)
                setTextIsSelectable(true)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = Ui.pill(this@TransactionActivity, Ui.FILL, 14)
            })
        }

        findViewById<View>(R.id.edit).setOnClickListener {
            Ui.push(this, Intent(this, EditExpenseActivity::class.java).putExtra(EditExpenseActivity.EXTRA_ID, e.id))
        }
        findViewById<View>(R.id.share).setOnClickListener { share(e) }
        findViewById<View>(R.id.delete).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete this expense?")
                .setPositiveButton("Delete") { _, _ -> db.delete(e.id); finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------------- sections ----------------

    private fun hero(root: LinearLayout, e: Expense) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        box.addView(Ui.iconBadge(this, Ui.categoryIcon(e.category), 72, inverted = true))
        box.addView(Ui.text(this, "To ${e.payee}", 15f, Ui.regular(this), Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        box.addView(Ui.text(this, Money.format(e.amount), 54f, Ui.light(this)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(Ui.text(this, e.category, 17f, Ui.medium(this), Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(Ui.text(this, "✓  Completed", 12f, Ui.medium(this)).apply {
            letterSpacing = 0.04f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = Ui.pill(this@TransactionActivity, Ui.FILL, 20)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        root.addView(box)
    }

    /** Turn a payment into a loan (or a loan repayment), or undo that. */
    private fun loanSection(root: LinearLayout, e: Expense) {
        section(root, "Lend & borrow")
        val linked = db.ledger().firstOrNull { it.expenseId == e.id }
        if (linked != null) {
            val what = if (linked.kind == Ledger.LENT) "Lent to ${linked.person}" else "Paid back to ${linked.person}"
            loanRow(root, if (linked.kind == Ledger.LENT) R.drawable.ic_cat_lent else R.drawable.ic_cat_repaid,
                what, "Not counted as spending", "Undo") {
                db.deleteLedgerForExpense(e.id)
                db.update(e.copy(category = Categories.guess(this, e.payee).takeUnless { Categories.isLoan(it) } ?: "Transfers"))
                render()
            }
            root.addView(Ui.hairline(this, Ui.dp(this, 52)))
            loanRow(root, R.drawable.ic_cat_transfers, "See everything with ${linked.person}", null, null) {
                Ui.push(this, Intent(this, LoansActivity::class.java).putExtra(LoansActivity.EXTRA_PERSON, linked.person))
            }
            return
        }
        loanRow(root, R.drawable.ic_cat_lent, "I lent this money", "Track it until they pay you back", null) {
            linkLoan(e, Ledger.LENT)
        }
        root.addView(Ui.hairline(this, Ui.dp(this, 52)))
        loanRow(root, R.drawable.ic_cat_repaid, "This was paying back a loan", "Counts against what you owe", null) {
            linkLoan(e, Ledger.PAID_BACK)
        }
    }

    private fun loanRow(root: LinearLayout, icon: Int, title: String, sub: String?, action: String?, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            Ui.pressable(this, 0.98f)
            setOnClickListener { onClick() }
        }
        r.addView(Ui.iconBadge(this, icon, 36).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 15f, Ui.medium(this)))
        sub?.let { texts.addView(Ui.text(this, it, 13f, Ui.regular(this), Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }) }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) r.addView(Ui.text(this, action, 14f, Ui.semibold(this), Ui.ACCENT).apply { setPadding(dp(16), 0, 0, 0) })
        else r.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_forward); alpha = 0.35f },
            LinearLayout.LayoutParams(dp(18), dp(18)))
        root.addView(r)
    }

    /** Ask who it was (payee prefilled), then move the payment out of spending and into the ledger. */
    private fun linkLoan(e: Expense, kind: String) {
        val name = android.widget.EditText(this).apply {
            setText(e.payee)
            setSelectAllOnFocus(true)
            typeface = Ui.regular(this@TransactionActivity)
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(name) }
        AlertDialog.Builder(this)
            .setTitle(if (kind == Ledger.LENT) "Who did you lend it to?" else "Whom did you pay back?")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val who = name.text.toString().trim().ifEmpty { e.payee }
                db.update(e.copy(category = if (kind == Ledger.LENT) Categories.LENT else Categories.REPAID))
                db.addLedger(LedgerEntry(person = who, amount = e.amount, kind = kind, timestamp = e.timestamp, expenseId = e.id))
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun payeeHistory(root: LinearLayout, e: Expense) {
        val key = Categories.key(e.payee)
        val all = db.all().filter { Categories.key(it.payee) == key }
        val month = Period.of(PeriodType.MONTH)
        val thisMonth = all.filter { it.timestamp in month.start until month.end }.sumOf { it.amount }

        section(root, "With ${e.payee}")
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(14))
        }
        fun stat(value: String, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@TransactionActivity, value, 22f, Ui.light(this@TransactionActivity)))
            addView(Ui.text(this@TransactionActivity, label, 12f, Ui.regular(this@TransactionActivity), Ui.MUTED).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        stats.addView(stat(Money.format(all.sumOf { it.amount }), "All time"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat(Money.format(thisMonth), "This month"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat("${all.size}", if (all.size == 1) "Payment" else "Payments"), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(stats)

        for (o in all.take(6)) {
            root.addView(Ui.hairline(this))
            val current = o.id == e.id
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(13), 0, dp(13))
                if (!current) {
                    isClickable = true
                    Ui.pressable(this, 0.98f)
                    setOnClickListener {
                        Ui.push(this@TransactionActivity, Intent(this@TransactionActivity, TransactionActivity::class.java).putExtra(EXTRA_ID, o.id))
                    }
                }
            }
            r.addView(Ui.text(this, shortDate.format(o.timestamp), 15f, Ui.regular(this), if (current) Ui.INK else 0xB3000000.toInt()),
                LinearLayout.LayoutParams(0, -2, 1f))
            if (current) r.addView(Ui.text(this, "This one", 13f, Ui.regular(this), Ui.MUTED).apply { setPadding(0, 0, dp(12), 0) })
            r.addView(Ui.text(this, Money.format(o.amount), 15f, Ui.medium(this)))
            root.addView(r)
        }
    }

    // ---------------- building blocks ----------------

    private fun section(root: LinearLayout, title: String) {
        root.addView(Ui.overline(this, title).apply {
            setPadding(0, dp(40), 0, dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    private fun row(root: LinearLayout, label: String, value: String, sub: String? = null,
                    action: String? = null, onAction: (() -> Unit)? = null) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, label, 12f, Ui.regular(this), Ui.MUTED))
        texts.addView(Ui.text(this, value, 15f, Ui.regular(this)).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.2f) })
        sub?.let { texts.addView(Ui.text(this, it, 13f, Ui.regular(this), Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }) }
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onAction != null) {
            r.addView(Ui.text(this, action, 14f, Ui.semibold(this), Ui.ACCENT).apply {
                setPadding(dp(16), dp(8), 0, dp(8))
                Ui.pressable(this, 0.94f)
                setOnClickListener { onAction() }
            })
        }
        root.addView(r)
    }

    // ---------------- actions ----------------

    private fun pickCategory(e: Expense) {
        val labels = Categories.all.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Category for ${e.payee}")
            .setSingleChoiceItems(labels, Categories.all.indexOf(e.category)) { d, i ->
                val cat = Categories.all[i]
                if (Categories.isLoan(e.category)) db.deleteLedgerForExpense(e.id)
                db.update(e.copy(category = cat))
                db.saveRule(Categories.key(e.payee), cat)
                d.dismiss()
                Toast.makeText(this, "${e.payee} will be logged as $cat from now on", Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(e: Expense) {
        val text = buildString {
            append("Paid ${Money.format(e.amount)} to ${e.payee}\n")
            append(longDate.format(e.timestamp))
            e.raw?.let { PaymentParser.account(it) }?.let { append("\nFrom $it") }
            e.ref?.let { append("\nRef: $it") }
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share payment"
        ))
    }
}
