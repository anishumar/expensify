package com.anishumar.kharcha.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.anishumar.kharcha.R
import com.anishumar.kharcha.capture.CaptureManager
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Source
import android.content.res.ColorStateList
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EditExpenseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_PAYEE = "payee"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_REF = "ref"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TIME = "time"
    }

    private val db by lazy { ExpenseDb.get(this) }
    private var existing: Expense? = null
    private var ts = System.currentTimeMillis()
    private val df = SimpleDateFormat("EEE, d MMM yyyy · h:mm a", Locale("en", "IN"))

    override fun finish() {
        super.finish()
        Ui.popTransition(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val title = findViewById<TextView>(R.id.title)

        val expenseId = intent.getLongExtra(EXTRA_ID, -1)
        existing = if (expenseId > 0) db.byId(expenseId) else null
        if (expenseId > 0) CaptureManager.cancelNotification(this, expenseId)

        val amount = findViewById<TextView>(R.id.amount)
        val payee = findViewById<TextView>(R.id.payee)
        val note = findViewById<TextView>(R.id.note)
        val chips = findViewById<ChipGroup>(R.id.chips)
        val dateBtn = findViewById<Button>(R.id.date)
        val remember = findViewById<CheckBox>(R.id.remember)

        val e = existing
        val startAmount = e?.amount ?: intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
        val startPayee = e?.payee ?: intent.getStringExtra(EXTRA_PAYEE) ?: ""
        val startCat = e?.category ?: intent.getStringExtra(EXTRA_CATEGORY) ?: "Other"
        ts = e?.timestamp ?: intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis())
        title.text = if (e != null) "Edit" else "New expense"

        if (startAmount > 0) amount.text = if (startAmount % 1.0 == 0.0) startAmount.toLong().toString() else startAmount.toString()
        payee.text = startPayee
        note.text = e?.note ?: ""

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        // a payment marked as lent / paid back keeps that category unless changed here
        val choices = if (startCat in Categories.all) Categories.all else Categories.all + startCat
        for (cat in choices) {
            chips.addView(Chip(this).apply {
                text = cat
                setChipIconResource(Ui.categoryIcon(cat))
                chipIconSize = Ui.dp(this@EditExpenseActivity, 16).toFloat()
                chipIconTint = ColorStateList(states, intArrayOf(Ui.PAPER, Ui.INK))
                isChipIconVisible = true
                tag = cat
                typeface = Ui.medium(this@EditExpenseActivity)
                textSize = 14f
                isCheckable = true
                isCheckedIconVisible = false
                chipStrokeWidth = 0f
                chipBackgroundColor = ColorStateList(states, intArrayOf(Ui.INK, Ui.FILL))
                setTextColor(ColorStateList(states, intArrayOf(Ui.PAPER, Ui.INK)))
                isChecked = cat == startCat
                id = View.generateViewId()
            })
        }

        dateBtn.text = df.format(ts)
        dateBtn.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            DatePickerDialog(this, { _, y, m, d ->
                TimePickerDialog(this, { _, h, min ->
                    c.set(y, m, d, h, min)
                    ts = c.timeInMillis
                    dateBtn.text = df.format(ts)
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        val src = e?.source ?: intent.getStringExtra(EXTRA_SOURCE) ?: Source.MANUAL
        val ref = e?.ref ?: intent.getStringExtra(EXTRA_REF)
        findViewById<TextView>(R.id.meta).text =
            "Logged from ${Source.label(src)}" + (ref?.let { "\nRef $it" } ?: "")

        val del = findViewById<Button>(R.id.delete)
        del.visibility = if (e != null) View.VISIBLE else View.GONE
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete this expense?")
                .setPositiveButton("Delete") { _, _ -> db.delete(e!!.id); finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val amt = amount.text.toString().replace(",", "").toDoubleOrNull()
            if (amt == null || amt <= 0) {
                Toast.makeText(this, "Enter an amount", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val p = payee.text.toString().trim().ifBlank { "Unknown" }
            val cat = chips.findViewById<Chip>(chips.checkedChipId)?.tag as? String ?: "Other"
            if (remember.isChecked) db.saveRule(Categories.key(p), cat)
            val n = note.text.toString().trim().ifBlank { null }
            if (e != null) {
                db.update(e.copy(amount = amt, payee = p, category = cat, timestamp = ts, note = n))
            } else {
                val draft = Expense(amount = amt, payee = p, category = cat, source = src, ref = ref, timestamp = ts, note = n)
                if (src == Source.SCREEN) CaptureManager.saveConfirmed(this, draft) else db.insert(draft)
            }
            finish()
        }
    }
}
