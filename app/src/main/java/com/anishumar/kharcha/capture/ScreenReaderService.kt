package com.anishumar.kharcha.capture

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.anishumar.kharcha.data.Categories
import com.anishumar.kharcha.data.Expense
import com.anishumar.kharcha.data.Money
import com.anishumar.kharcha.data.Source
import com.anishumar.kharcha.ui.EditExpenseActivity
import com.anishumar.kharcha.ui.Ui
import java.text.SimpleDateFormat
import java.util.Locale
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

        /** Shopping / food / ride apps: the merchant is the app itself, as it shows on the card SMS. */
        private val MERCHANT_APPS = mapOf(
            "in.swiggy.android" to "Swiggy",
            "com.application.zomato" to "Zomato",
            "com.grofers.customerapp" to "Blinkit",
            "com.zeptoconsumerapp" to "Zepto",
            "com.bigbasket.mobileapp" to "BigBasket",
            "com.jpl.jiomart" to "JioMart",
            "in.amazon.mShop.android.shopping" to "Amazon",
            "com.flipkart.android" to "Flipkart",
            "com.myntra.android" to "Myntra",
            "com.fsn.nykaa" to "Nykaa",
            "com.ubercab" to "Uber",
            "com.olacabs.customer" to "Ola",
            "com.rapido.passenger" to "Rapido",
            "com.makemytrip" to "MakeMyTrip",
            "com.bt.bms" to "BookMyShow",
            "com.Dominos" to "Domino's",
            "com.done.faasos" to "EatSure"
        )
        private val EASE_DRAWER = PathInterpolator(0.32f, 0.72f, 0f, 1f)

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
        removeCard(animated = false)
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
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Ui.light(this@ScreenReaderService)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.INK)
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(8).toFloat()
            alpha = 0.94f
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
        var tracker: VelocityTracker? = null
        v.setOnTouchListener { _, e ->
            // the bubble window moves with the finger, so track velocity in screen coordinates
            val screen = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // grabbing it mid-flight stops the springs where it is right now
                    springX?.cancel(); springY?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(screen) }
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).setInterpolator(Ui.EASE_OUT).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(screen)
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        // 1:1 with the finger, keeping the offset from where it was grabbed
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Ui.EASE_OUT).start()
                    tracker?.addMovement(screen)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    if (moved) settle(v, lp, size, vx, vy)
                    else if (e.actionMasked == MotionEvent.ACTION_UP) onBubbleTap()
                }
            }
            screen.recycle()
            true
        }
        runCatching { wm.addView(v, lp) }.onSuccess { bubble = v; bubbleParams = lp }
    }

    private var springX: SpringAnimation? = null
    private var springY: SpringAnimation? = null

    /**
     * Throw the bubble: project where the flick is heading (Apple's deceleration formula),
     * pick the edge nearest that point, and spring there carrying the finger's velocity.
     */
    private fun settle(v: View, lp: WindowManager.LayoutParams, size: Int, vx: Float, vy: Float) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        fun project(velocity: Float, rate: Float = 0.998f) = velocity / 1000f * rate / (1f - rate)
        val endX = if (lp.x + size / 2 + project(vx) < w / 2) dp(4) else w - size - dp(4)
        val endY = (lp.y + project(vy)).toInt().coerceIn(dp(48), h - size - dp(96))
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val save = { prefs.edit().putInt("bx", endX).putInt("by", endY).apply() }
        if (Ui.reducedMotion(this)) {
            lp.x = endX; lp.y = endY; runCatching { wm.updateViewLayout(v, lp) }; save(); return
        }
        fun spring(from: Int, to: Int, velocity: Float, damping: Float, apply: (Int) -> Unit) =
            SpringAnimation(FloatValueHolder(from.toFloat())).apply {
                spring = SpringForce(to.toFloat()).setDampingRatio(damping).setStiffness(stiffness(0.4f))
                setStartVelocity(velocity)
                addUpdateListener { _, value, _ -> apply(value.toInt()); runCatching { wm.updateViewLayout(v, lp) } }
                start()
            }
        // independent X and Y springs; X gets a little bounce because a flick carried momentum into it
        springX = spring(lp.x, endX, vx, 0.8f) { lp.x = it }.apply { addEndListener { _, canceled, _, _ -> if (!canceled) save() } }
        springY = spring(lp.y, endY, vy, 1f) { lp.y = it }
    }

    /** Apple's spring "response" (seconds) → stiffness. */
    private fun stiffness(response: Float) = ((2 * Math.PI / response) * (2 * Math.PI / response)).toFloat()

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ---------------- screen reading ----------------

    private var reading = false

    /**
     * Read whatever is on screen. Accessibility text first (instant, works on payment apps
     * that block screenshots); if that has no amount, or a shopping app's screen has no
     * "To pay / Grand total" in it, read a screenshot with on-device text recognition.
     */
    private fun onBubbleTap() {
        if (reading) return
        val (text, app) = readScreenText()
        val parsed = text.takeIf { it.isNotBlank() }?.let { PaymentParser.parse(it, lenient = true) }
        val merchant = app in MERCHANT_APPS
        if (parsed != null && (!merchant || PaymentParser.hasLabelledTotal(text))) {
            confirm(text, app, parsed)
            return
        }
        reading = true
        val b = bubble
        b?.visibility = View.INVISIBLE            // keep the bubble out of the screenshot
        handler.postDelayed({
            ScreenOcr.read(this) { ocr ->
                b?.visibility = View.VISIBLE
                reading = false
                val seen = ocr?.let { PaymentParser.normalizeOcr(it) }
                val fromImage = seen?.let { PaymentParser.parse(it, lenient = true) }
                when {
                    fromImage != null -> confirm(seen + "\n" + text, app, fromImage)
                    parsed != null -> confirm(text, app, parsed)
                    else -> toast("No amount found on this screen.")
                }
            }
        }, 120)
    }

    private fun confirm(text: String, app: String?, p: PaymentParser.Parsed) {
        val payee = MERCHANT_APPS[app] ?: p.payee.ifBlank { "Unknown" }
        // the time printed on the screen (order / payment time), else now
        val ts = PaymentParser.timestamp(text) ?: System.currentTimeMillis()
        val draft = Expense(
            amount = p.amount, payee = payee, category = Categories.guess(this, payee),
            source = Source.SCREEN, ref = p.ref, timestamp = ts, raw = text
        )
        showCard(draft)
    }

    /** Text of the app on screen, and that app's package. */
    private fun readScreenText(): Pair<String, String?> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { if (it.isActive) 1 else 0 }
                .take(1)
                .mapNotNullTo(roots) { it.root }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        if (roots.isEmpty()) return "" to null
        val app = roots.first().packageName?.toString()
        val sb = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            // React Native / Compose screens (Swiggy, Zomato) nest text very deep
            if (n == null || depth > 150 || count > 8000) return
            count++
            if (n.packageName?.toString() == packageName) return
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) sb.append(t).append('\n')
            if (!d.isNullOrEmpty() && d != t) sb.append(d).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        return sb.toString() to app
    }

    // ---------------- confirm card ----------------

    private fun showCard(draft: Expense) {
        removeCard(animated = false)
        var selected = draft.category

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(12).toFloat()
        }
        root.addView(TextView(this).apply {
            text = Money.format(draft.amount)
            textSize = 40f
            typeface = Ui.displayLight(this@ScreenReaderService)
            letterSpacing = -0.011f
            setTextColor(Ui.INK)
        })
        root.addView(TextView(this).apply {
            val time = SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN")).format(draft.timestamp)
                .replace("AM", "am").replace("PM", "pm")
            text = "to ${draft.payee}  ·  $time" + (draft.ref?.let { "\nRef $it" } ?: "")
            textSize = 14f
            typeface = Ui.regular(this@ScreenReaderService)
            setTextColor(Ui.MUTED)
            setPadding(0, dp(4), 0, dp(18))
        })

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableMapOf<String, TextView>()
        fun paint() = chips.forEach { (cat, tv) ->
            val on = cat == selected
            (tv.background as GradientDrawable).setColor(if (on) Ui.INK else Ui.FILL)
            tv.setTextColor(if (on) Color.WHITE else Ui.INK)
            tv.compoundDrawablesRelative[0]?.setTint(if (on) Color.WHITE else Ui.INK)
        }
        val ordered = listOf(selected) + Categories.all.filter { it != selected }
        for (cat in ordered) {
            val tv = TextView(this).apply {
                text = cat
                textSize = 14f
                typeface = Ui.medium(this@ScreenReaderService)
                getDrawable(Ui.categoryIcon(cat))?.mutate()?.let { icon ->
                    icon.setBounds(0, 0, dp(16), dp(16))
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat() }
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
            setPadding(0, dp(20), 0, 0)
        }
        fun btn(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Ui.medium(this@ScreenReaderService)
            setPadding(dp(if (primary) 28 else 14), dp(12), dp(if (primary) 28 else 14), dp(12))
            setTextColor(if (primary) Color.WHITE else Ui.INK)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (primary) Ui.INK else Color.TRANSPARENT)
            }
            Ui.pressable(this, 0.96f)
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
                    .putExtra(EditExpenseActivity.EXTRA_TIME, draft.timestamp)
                    .putExtra(EditExpenseActivity.EXTRA_SOURCE, Source.SCREEN)
            )
        })
        buttons.addView(btn("Save", true) {
            val e = draft.copy(category = selected)
            buttons.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            )
            Thread { CaptureManager.saveConfirmed(applicationContext, e) }.start()
            removeCard()
            toast("Saved ${Money.format(e.amount)} · ${e.category}")
        })
        root.addView(buttons)

        // dim background, tap outside = cancel
        val dim = ColorDrawable(0x80000000.toInt())
        val scrim = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            background = dim
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

        // Rises from the bottom edge; removeCard() sends it back down the same way
        if (Ui.reducedMotion(this)) return
        dim.alpha = 0
        ObjectAnimator.ofInt(dim, "alpha", 0, 255).setDuration(250).start()
        root.translationY = resources.displayMetrics.heightPixels / 2f
        root.animate().translationY(0f).setDuration(450).setInterpolator(EASE_DRAWER).start()
    }

    private fun removeCard(animated: Boolean = true) {
        val c = card ?: return
        card = null
        val sheet = (c as? android.view.ViewGroup)?.getChildAt(0)
        if (!animated || Ui.reducedMotion(this) || sheet == null) {
            runCatching { wm.removeView(c) }
            return
        }
        (c.background as? ColorDrawable)?.let { ObjectAnimator.ofInt(it, "alpha", it.alpha, 0).setDuration(250).start() }
        sheet.animate().translationY(sheet.height + dp(24).toFloat()).setDuration(300).setInterpolator(EASE_DRAWER)
            .withEndAction { runCatching { wm.removeView(c) } }.start()
    }

    private fun toast(msg: String) = handler.post {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
