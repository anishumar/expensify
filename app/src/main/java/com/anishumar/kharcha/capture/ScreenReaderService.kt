package com.anishumar.kharcha.capture

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.data.Source
import com.anishumar.kharcha.ui.EditExpenseActivity
import kotlin.math.abs

/**
 * Hosts the floating ₹ bubble (as an accessibility overlay, so no extra
 * "draw over apps" permission is needed). Tapping the bubble reads the text
 * of the app underneath and shows a confirm card.
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        const val PREFS = "kharcha"
        const val KEY_BUBBLE = "show_bubble"
        const val ACTION_REFRESH = "com.anishumar.kharcha.REFRESH_BUBBLE"
        @Volatile var running: ScreenReaderService? = null

        fun refresh(ctx: Context) {
            running?.let { s -> s.handler.post { s.applyBubbleSetting() } }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var card: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        wm = getSystemService(WindowManager::class.java)
        applyBubbleSetting()
    }

    override fun onDestroy() {
        removeCard()
        removeBubble()
        running = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun applyBubbleSetting() {
        val show = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BUBBLE, true)
        if (show) addBubble() else removeBubble()
    }

    // ---------------- bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (bubble != null) return
        val size = dp(52)
        val v = TextView(this).apply {
            text = "₹"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0F766E.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            alpha = 0.92f
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bx", resources.displayMetrics.widthPixels - size - dp(8))
            y = prefs.getInt("by", resources.displayMetrics.heightPixels / 3)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // snap to nearest edge
                        val w = resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + size / 2 < w / 2) dp(4) else w - size - dp(4)
                        wm.updateViewLayout(v, lp)
                        prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
                    } else onBubbleTap()
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(v, lp) }.onSuccess { bubble = v; bubbleParams = lp }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ---------------- screen reading ----------------

    private fun onBubbleTap() {
        bubble?.animate()?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(80)?.withEndAction {
            bubble?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(80)?.start()
        }?.start()
        val text = readScreenText()
        if (text.isBlank()) {
            toast("Couldn't read this screen. Open the payment success screen and tap again.")
            return
        }
        val p = PaymentParser.parse(text, lenient = true)
        if (p == null) {
            toast("No payment amount found on this screen.")
            return
        }
        val payee = p.payee.ifBlank { "Unknown" }
        val draft = Expense(
            amount = p.amount, payee = payee, category = Categories.guess(this, payee),
            source = Source.SCREEN, ref = p.ref, raw = text
        )
        showCard(draft)
    }

    private fun readScreenText(): String {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { if (it.isActive) 1 else 0 }
                .take(1)
                .mapNotNullTo(roots) { it.root }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        if (roots.isEmpty()) return ""
        val sb = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || count > 3000) return
            count++
            if (n.packageName?.toString() == packageName) return
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) sb.append(t).append('\n')
            if (!d.isNullOrEmpty() && d != t) sb.append(d).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        return sb.toString()
    }

    // ---------------- confirm card ----------------

    private fun showCard(draft: Expense) {
        removeCard()
        var selected = draft.category

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(12).toFloat()
        }
        root.addView(TextView(this).apply {
            text = Money.format(draft.amount)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt())
        })
        root.addView(TextView(this).apply {
            text = "to ${draft.payee}" + (draft.ref?.let { "  ·  Ref $it" } ?: "")
            textSize = 14f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableMapOf<String, TextView>()
        fun paint() = chips.forEach { (cat, tv) ->
            val on = cat == selected
            val c = Categories.colors[cat] ?: 0xFF9CA3AF.toInt()
            (tv.background as GradientDrawable).apply {
                setColor(if (on) c else 0xFFF3F4F6.toInt())
            }
            tv.setTextColor(if (on) Color.WHITE else 0xFF374151.toInt())
        }
        val ordered = listOf(selected) + Categories.all.filter { it != selected }
        for (cat in ordered) {
            val tv = TextView(this).apply {
                text = "${Categories.emoji[cat]} $cat"
                textSize = 14f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
                setOnClickListener { selected = cat; paint() }
            }
            chips[cat] = tv
            chipRow.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        paint()
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        fun btn(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setTextColor(if (primary) Color.WHITE else 0xFF0F766E.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (primary) 0xFF0F766E.toInt() else Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }
        buttons.addView(btn("Cancel", false) { removeCard() })
        buttons.addView(btn("Edit", false) {
            removeCard()
            startActivity(
                Intent(this, EditExpenseActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EditExpenseActivity.EXTRA_AMOUNT, draft.amount)
                    .putExtra(EditExpenseActivity.EXTRA_PAYEE, draft.payee)
                    .putExtra(EditExpenseActivity.EXTRA_CATEGORY, selected)
                    .putExtra(EditExpenseActivity.EXTRA_REF, draft.ref)
                    .putExtra(EditExpenseActivity.EXTRA_SOURCE, Source.SCREEN)
            )
        })
        buttons.addView(btn("Save", true) {
            val e = draft.copy(category = selected)
            Thread { CaptureManager.saveConfirmed(applicationContext, e) }.start()
            removeCard()
            toast("Saved ${Money.format(e.amount)} · ${e.category}")
        })
        root.addView(buttons)

        // dim background, tap outside = cancel
        val scrim = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setBackgroundColor(0x66000000)
            setPadding(dp(12), 0, dp(12), dp(24))
            setOnClickListener { removeCard() }
            addView(root, LinearLayout.LayoutParams(-1, -2))
        }
        root.setOnClickListener { /* swallow */ }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(scrim, lp) }.onSuccess { card = scrim }
    }

    private fun removeCard() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    private fun toast(msg: String) = handler.post {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
