package com.anishumar.kharcha.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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
import com.anishumar.kharcha.data.ExpenseDb
import com.anishumar.kharcha.data.Source
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Permissions, data import/export and the bubble toggle — opened from the ☰ on the home screen. */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SMS = 11
        private const val REQ_NOTIF = 12

        fun accessibilityOn(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, ScreenReaderService::class.java).flattenToString()
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            return splitter.any { it.equals(me, ignoreCase = true) }
        }

        fun notifListenerOn(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun smsOn(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

        fun postNotifOn(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun setupComplete(ctx: Context) = accessibilityOn(ctx) && notifListenerOn(ctx) && smsOn(ctx) && postNotifOn(ctx)
    }

    private val prefs by lazy { getSharedPreferences(ScreenReaderService.PREFS, MODE_PRIVATE) }
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
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
        while (root.childCount > 1) root.removeViewAt(1)

        section(root, "Permissions")
        permission(root, "Payment bubble", "Tap the floating ₹ on a payment screen to log it.", accessibilityOn(this)) {
            AlertDialog.Builder(this)
                .setTitle("Turn on the bubble")
                .setMessage(
                    "Android will open Accessibility settings.\n\n" +
                        "Tap \"Installed apps\" → \"Expensify payment bubble\" → turn it on.\n\n" +
                        "If Android says it's a \"restricted setting\": Settings → Apps → Expensify → ⋮ → " +
                        "\"Allow restricted settings\", then try again.\n\n" +
                        "Expensify only reads the screen when you tap the bubble, and nothing leaves your phone."
                )
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("Later", null)
                .show()
        }
        permission(root, "Payment app notifications", "Log GPay, PhonePe and Paytm payments automatically.", notifListenerOn(this)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permission(root, "Bank SMS", "Log card and account debits from your bank's messages.", smsOn(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), REQ_SMS)
        }
        permission(root, "Alerts", "Weekly and monthly summaries.", postNotifOn(this), last = true) {
            if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        section(root, "Bubble")
        val showRow = row(root, "Show ₹ bubble", "Hide it when you don't need it.", last = true)
        showRow.addView(MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(ScreenReaderService.KEY_BUBBLE, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(ScreenReaderService.KEY_BUBBLE, on).apply()
                ScreenReaderService.refresh(this@SettingsActivity)
            }
        })

        section(root, "Data")
        row(root, "Import past SMS", "Scan the last 90 days of bank messages.").apply {
            addView(chevron()); Ui.pressable(this, 0.98f); setOnClickListener { importSms() }
        }
        row(root, "Export CSV", "Every expense, to share or open in a spreadsheet.", last = true).apply {
            addView(chevron()); Ui.pressable(this, 0.98f); setOnClickListener { exportCsv() }
        }

        root.addView(Ui.text(this, "Everything stays on this phone.", 13f, Ui.regular(this), Ui.MUTED).apply {
            setPadding(0, dp(40), 0, 0)
        })
    }

    // ---------------- rows ----------------

    private fun section(root: LinearLayout, title: String) {
        root.addView(Ui.overline(this, title).apply { setPadding(0, dp(36), 0, dp(4)) })
    }

    private fun row(root: LinearLayout, title: String, desc: String, last: Boolean = false): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 16f, Ui.medium(this)))
        texts.addView(Ui.text(this, desc, 13f, Ui.regular(this), Ui.MUTED).apply {
            setPadding(0, dp(4), dp(12), 0)
            setLineSpacing(0f, 1.2f)
        })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(r)
        if (!last) root.addView(Ui.hairline(this))
        return r
    }

    private fun permission(root: LinearLayout, title: String, desc: String, on: Boolean, last: Boolean = false, enable: () -> Unit) {
        val r = row(root, title, desc, last)
        if (on) r.addView(Ui.text(this, "On", 14f, Ui.regular(this), Ui.MUTED))
        else {
            r.addView(Ui.text(this, "Turn on", 14f, Ui.semibold(this), Ui.ACCENT))
            Ui.pressable(r, 0.98f)
            r.setOnClickListener { enable() }
        }
    }

    private fun chevron() = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_forward)
        alpha = 0.35f
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
    }

    // ---------------- actions ----------------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        if (requestCode == REQ_SMS && smsOn(this)) {
            AlertDialog.Builder(this)
                .setTitle("Import past payments?")
                .setMessage("Expensify can scan bank SMS from the last 90 days and add those debits now.")
                .setPositiveButton("Import") { _, _ -> importSms() }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    private fun importSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), REQ_SMS)
            return
        }
        toast("Scanning SMS…")
        Thread {
            val n = runCatching { SmsImporter.importInbox(applicationContext) }.getOrDefault(0)
            runOnUiThread { toast("Added $n payments from SMS") }
        }.start()
    }

    private fun exportCsv() {
        val list = ExpenseDb.get(this).all()
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
        val f = File(dir, "expensify-expenses.csv").apply { writeText(csv) }
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
