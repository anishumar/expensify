package com.anishumar.kharcha.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Ledger
import com.anishumar.kharcha.data.LedgerEntry
import com.anishumar.kharcha.data.Money

/** "Who and how much" sheet for the lend & borrow ledger. */
object LoanDialog {

    /**
     * @param settle false: choose Lent / Borrowed. true: choose They paid me / I paid them.
     */
    fun show(
        a: Activity,
        settle: Boolean = false,
        kind: String = if (settle) Ledger.GOT_BACK else Ledger.LENT,
        person: String = "",
        amount: Double? = null,
        onSaved: () -> Unit = {}
    ) {
        fun dp(v: Int) = Ui.dp(a, v)
        val db = ExpenseDb.get(a)
        val options = if (settle) listOf(Ledger.GOT_BACK to "They paid me", Ledger.PAID_BACK to "I paid them")
            else listOf(Ledger.LENT to "I lent", Ledger.BORROWED to "I borrowed")
        var chosen = kind

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val seg = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.pill(a, Ui.FILL, 10)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        for ((k, label) in options) {
            seg.addView(Ui.text(a, label, 13f, Ui.medium(a)).apply {
                gravity = Gravity.CENTER
                setOnClickListener { chosen = k; paintSegments(a, seg, options.map { it.first }, chosen) }
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        paintSegments(a, seg, options.map { it.first }, chosen)
        root.addView(seg)

        fun field() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        val name = AutoCompleteTextView(a).apply {
            hint = "Name"
            setText(person)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
            threshold = 1
            setAdapter(ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, db.people().map { it.first }))
            style(a, this)
        }
        root.addView(name, field())
        val amt = EditText(a).apply {
            hint = "Amount (₹)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            amount?.let { setText(if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()) }
            style(a, this)
        }
        root.addView(amt, field())
        val note = EditText(a).apply {
            hint = "Note (optional)"
            inputType = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            style(a, this)
        }
        root.addView(note, field())

        AlertDialog.Builder(a)
            .setTitle(if (settle) "Settle up" else "Lend or borrow")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val who = name.text.toString().trim()
                val value = amt.text.toString().replace(",", "").toDoubleOrNull()
                if (who.isEmpty() || value == null || value <= 0) {
                    Toast.makeText(a, "Add a name and an amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                db.addLedger(LedgerEntry(person = who, amount = value, kind = chosen, note = note.text.toString().trim().ifEmpty { null }))
                Toast.makeText(a, "${Ledger.label(chosen)} ${Money.format(value)} · $who", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun paintSegments(a: Activity, seg: LinearLayout, kinds: List<String>, chosen: String) {
        for (i in 0 until seg.childCount) {
            val on = kinds[i] == chosen
            (seg.getChildAt(i) as TextView).apply {
                background = if (on) Ui.pill(a, Ui.INK, 8) else null
                setTextColor(if (on) Ui.PAPER else Ui.INK)
            }
        }
    }

    private fun style(a: Activity, e: EditText) {
        e.typeface = Ui.regular(a)
        e.textSize = 16f
        e.setTextColor(Ui.INK)
        e.setHintTextColor(Ui.FAINT)
        e.background = Ui.pill(a, Ui.FILL, 12)
        e.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14))
    }
}
