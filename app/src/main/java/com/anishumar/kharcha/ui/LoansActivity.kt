package com.anishumar.kharcha.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.anishumar.kharcha.R
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Ledger
import com.anishumar.kharcha.data.LedgerEntry
import com.anishumar.kharcha.data.Money
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Lend & borrow. Without [EXTRA_PERSON]: everyone with a balance. With it: one person's
 * history and a "Settle up" action.
 */
class LoansActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON = "person"
    }

    private val db by lazy { ExpenseDb.get(this) }
    private fun dp(v: Int) = Ui.dp(this, v)
    private val date = SimpleDateFormat("d MMM yyyy", Locale("en", "IN"))
    private var person: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)
        person = intent.getStringExtra(EXTRA_PERSON)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.fab).apply {
            Ui.pressable(this, 0.92f)
            setOnClickListener { LoanDialog.show(this@LoansActivity, person = person ?: "") { render() } }
        }
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
        val root = findViewById<LinearLayout>(R.id.content)
        root.removeAllViews()
        val p = person
        if (p == null) renderEveryone(root) else renderPerson(root, p)
    }

    // ---------------- everyone ----------------

    private fun renderEveryone(root: LinearLayout) {
        root.addView(Ui.text(this, "Lend & borrow", 34f, Ui.semibold(this)))
        val people = db.people().map { (name, entries) -> Triple(name, entries, Ledger.balance(entries)) }
        val owedToYou = people.filter { it.third > 0.009 }.sumOf { it.third }
        val youOwe = people.filter { it.third < -0.009 }.sumOf { -it.third }

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(24), 0, dp(8))
        }
        stats.addView(stat("You'll get", owedToYou, Ui.INK), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(stat("You owe", youOwe, if (youOwe > 0) Ui.ACCENT else Ui.INK), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(stats)

        if (people.isEmpty()) {
            root.addView(Ui.text(this,
                "Keep track of money you lend and borrow. Tap + to add someone, or open a payment and mark it as lent.",
                15f, Ui.regular(this), Ui.MUTED).apply {
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(24), 0, 0)
            })
            return
        }

        val open = people.filter { abs(it.third) > 0.009 }
        val settled = people.filter { abs(it.third) <= 0.009 }
        if (open.isNotEmpty()) section(root, "Open")
        open.forEachIndexed { i, (name, entries, bal) -> if (i > 0) root.addView(Ui.hairline(this, dp(60))); root.addView(personRow(name, entries, bal)) }
        if (settled.isNotEmpty()) section(root, "Settled")
        settled.forEachIndexed { i, (name, entries, bal) -> if (i > 0) root.addView(Ui.hairline(this, dp(60))); root.addView(personRow(name, entries, bal)) }
    }

    private fun stat(label: String, value: Double, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.overline(this@LoansActivity, label))
        addView(Ui.text(this@LoansActivity, Money.format(value), 30f, Ui.light(this@LoansActivity), color).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun personRow(name: String, entries: List<LedgerEntry>, bal: Double): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            Ui.pressable(this, 0.98f)
            setOnClickListener {
                Ui.push(this@LoansActivity, Intent(this@LoansActivity, LoansActivity::class.java).putExtra(EXTRA_PERSON, name))
            }
        }
        r.addView(Ui.monogram(this, name, 44).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(Ui.text(this, name, 16f, Ui.medium(this)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        mid.addView(Ui.text(this, "Last on ${date.format(entries.maxOf { it.timestamp })}", 13f, Ui.regular(this), Ui.MUTED).apply {
            setPadding(0, dp(5), 0, 0)
        })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(Ui.text(this, Money.format(abs(bal)), 16f, Ui.medium(this), if (bal < -0.009) Ui.ACCENT else Ui.INK))
        right.addView(Ui.text(this, when {
            bal > 0.009 -> "owes you"
            bal < -0.009 -> "you owe"
            else -> "settled"
        }, 13f, Ui.regular(this), Ui.MUTED).apply { setPadding(0, dp(5), 0, 0) })
        r.addView(right)
        return r
    }

    // ---------------- one person ----------------

    private fun renderPerson(root: LinearLayout, name: String) {
        val entries = db.people().firstOrNull { it.first.equals(name, ignoreCase = true) }?.second.orEmpty()
        val bal = Ledger.balance(entries)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        hero.addView(Ui.monogram(this, name, 64, inverted = true))
        hero.addView(Ui.text(this, name, 17f, Ui.medium(this)).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        hero.addView(Ui.text(this, Money.format(abs(bal)), 54f, Ui.light(this), if (bal < -0.009) Ui.ACCENT else Ui.INK).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        hero.addView(Ui.text(this, when {
            bal > 0.009 -> "$name owes you"
            bal < -0.009 -> "You owe $name"
            else -> "All settled"
        }, 15f, Ui.regular(this), Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        if (abs(bal) > 0.009) {
            hero.addView(Ui.text(this, "Settle up", 15f, Ui.medium(this), Ui.PAPER).apply {
                gravity = Gravity.CENTER
                background = Ui.pill(this@LoansActivity, Ui.INK, 24)
                setPadding(dp(32), dp(14), dp(32), dp(14))
                Ui.pressable(this, 0.96f)
                setOnClickListener {
                    LoanDialog.show(this@LoansActivity, settle = true,
                        kind = if (bal > 0) Ledger.GOT_BACK else Ledger.PAID_BACK,
                        person = name, amount = abs(bal)) { render() }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        }
        root.addView(hero)

        section(root, "History")
        entries.forEachIndexed { i, e ->
            if (i > 0) root.addView(Ui.hairline(this, dp(52)))
            root.addView(entryRow(e))
        }
    }

    private fun entryRow(e: LedgerEntry): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            setOnLongClickListener { confirmDelete(e); true }
        }
        val icon = when (e.kind) {
            Ledger.LENT -> R.drawable.ic_cat_lent
            Ledger.BORROWED -> R.drawable.ic_cat_borrowed
            else -> R.drawable.ic_cat_repaid
        }
        r.addView(Ui.iconBadge(this, icon, 36).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(Ui.text(this, Ledger.label(e.kind), 15f, Ui.medium(this)))
        val sub = listOfNotNull(date.format(e.timestamp), e.note, if (e.expenseId != null) "from a logged payment" else null)
        mid.addView(Ui.text(this, sub.joinToString("  ·  "), 13f, Ui.regular(this), Ui.MUTED).apply {
            setPadding(0, dp(5), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Ui.text(this, Money.format(e.amount), 15f, Ui.medium(this)))
        return r
    }

    private fun confirmDelete(e: LedgerEntry) {
        if (e.expenseId != null) {
            AlertDialog.Builder(this)
                .setMessage("This came from a logged payment. Open the payment and tap Undo to remove it.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Delete \"${Ledger.label(e.kind)} ${Money.format(e.amount)}\"?")
            .setPositiveButton("Delete") { _, _ -> db.deleteLedger(e.id); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(Ui.overline(this, title).apply { setPadding(0, dp(36), 0, dp(4)) })
    }
}
