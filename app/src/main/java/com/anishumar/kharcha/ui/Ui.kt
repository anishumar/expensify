package com.anishumar.kharcha.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.anishumar.kharcha.R
import com.anishumar.kharcha.data.Categories
import kotlin.math.exp

/**
 * Shared look and feel: black on white with one red accent, Inter (the closest open
 * match to Apple's SF Pro) with size-specific tracking, and Apple-style motion.
 */
object Ui {
    const val INK = 0xFF000000.toInt()
    const val PAPER = 0xFFFFFFFF.toInt()
    const val MUTED = 0x8C000000.toInt()
    const val FAINT = 0x4D000000
    const val HAIRLINE = 0x14000000
    const val FILL = 0x0A000000
    const val ACCENT = 0xFFFF3B30.toInt()

    /** ease-out: cubic-bezier(0.23, 1, 0.32, 1) */
    val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private val cache = HashMap<Int, Typeface>()
    private fun font(ctx: Context, id: Int): Typeface =
        cache.getOrPut(id) { ResourcesCompat.getFont(ctx, id) ?: Typeface.DEFAULT }

    fun light(ctx: Context) = font(ctx, R.font.inter_light)
    fun regular(ctx: Context) = font(ctx, R.font.inter_regular)
    fun medium(ctx: Context) = font(ctx, R.font.inter_medium)
    fun semibold(ctx: Context) = font(ctx, R.font.inter_semibold)
    fun displayLight(ctx: Context) = font(ctx, R.font.inter_display_light)
    fun displaySemibold(ctx: Context) = font(ctx, R.font.inter_display_semibold)

    /** Like SF Pro Text → SF Pro Display at 20pt: large text switches to the Display cut. */
    private fun optical(ctx: Context, face: Typeface, size: Float): Typeface = when {
        size < 20f -> face
        face == light(ctx) -> displayLight(ctx)
        face == semibold(ctx) -> displaySemibold(ctx)
        else -> face
    }

    /**
     * Size-specific tracking, like SF Pro's tracking table: slightly open at caption
     * sizes, tighter as text grows (Inter's dynamic-metrics curve, in em).
     */
    fun tracking(sizeSp: Float) = (-0.0223 + 0.185 * exp(-0.1745 * sizeSp)).toFloat()

    /** The Display cut is already drawn tighter, so it needs about half the negative tracking. */
    private fun displayTracking(sizeSp: Float) = tracking(sizeSp) / 2

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dp(ctx: Context, v: Int) = dp(ctx, v.toFloat())

    fun text(ctx: Context, s: CharSequence, size: Float, face: Typeface, color: Int = INK) = TextView(ctx).apply {
        text = s
        textSize = size
        typeface = optical(ctx, face, size)
        letterSpacing = if (typeface != face) displayTracking(size) else tracking(size)
        setTextColor(color)
        includeFontPadding = false
    }

    /** Small caps label: "SPENT", "DETAILS". */
    fun overline(ctx: Context, s: String) = text(ctx, s.uppercase(), 12f, medium(ctx), MUTED).apply {
        letterSpacing = 0.06f
    }

    fun hairline(ctx: Context, insetStart: Int = 0) = View(ctx).apply {
        setBackgroundColor(HAIRLINE)
        layoutParams = LinearLayout.LayoutParams(-1, 1.coerceAtLeast(dp(ctx, 0.5f))).apply { marginStart = insetStart }
    }

    /** Round initial — the only "icon" in the app. */
    fun monogram(ctx: Context, name: String, sizeDp: Int, inverted: Boolean = false) =
        text(ctx, name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·", sizeDp * 0.4f, medium(ctx),
            if (inverted) PAPER else INK).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (inverted) INK else FILL)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    fun categoryIcon(category: String): Int = when (category) {
        "Food" -> R.drawable.ic_cat_food
        "Groceries" -> R.drawable.ic_cat_groceries
        "Travel" -> R.drawable.ic_cat_travel
        "Fuel" -> R.drawable.ic_cat_fuel
        "Shopping" -> R.drawable.ic_cat_shopping
        "Bills" -> R.drawable.ic_cat_bills
        "Rent" -> R.drawable.ic_cat_rent
        "Entertainment" -> R.drawable.ic_cat_entertainment
        "Health" -> R.drawable.ic_cat_health
        "Education" -> R.drawable.ic_cat_education
        "Transfers" -> R.drawable.ic_cat_transfers
        Categories.LENT -> R.drawable.ic_cat_lent
        Categories.REPAID -> R.drawable.ic_cat_repaid
        else -> R.drawable.ic_cat_other
    }

    /** Line-art icon in a soft round badge. */
    fun iconBadge(ctx: Context, icon: Int, sizeDp: Int, inverted: Boolean = false) = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (inverted) INK else FILL)
        }
        addView(ImageView(ctx).apply {
            setImageResource(icon)
            if (inverted) setColorFilter(PAPER)
        }, FrameLayout.LayoutParams(dp(ctx, sizeDp * 0.52f), dp(ctx, sizeDp * 0.52f), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    fun pill(ctx: Context, color: Int, radiusDp: Int = 22) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(color)
    }

    // ---------------- motion ----------------

    /** System "Remove animations" / animator scale 0 → use static changes instead. */
    fun reducedMotion(ctx: Context) =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /**
     * Feedback on touch-down, not on release: shrink + dim instantly-ish (100ms), settle back
     * on release. Animates from the current value, so quick re-taps never jump.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, scale: Float = 0.97f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).alpha(0.7f)
                    .setDuration(100).setInterpolator(EASE_OUT).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200).setInterpolator(EASE_OUT).start()
            }
            false
        }
    }

    /** Push a screen in from the right; [popTransition] sends it back out the same way. */
    fun push(from: Activity, intent: Intent) {
        from.startActivity(intent)
        @Suppress("DEPRECATION")
        from.overridePendingTransition(R.anim.push_enter, R.anim.push_exit)
    }

    fun popTransition(a: Activity) {
        @Suppress("DEPRECATION")
        a.overridePendingTransition(R.anim.pop_enter, R.anim.pop_exit)
    }
}
