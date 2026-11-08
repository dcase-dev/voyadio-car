package com.voyadio.automotive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.voyadio.automotive.geo.GlobeView

/** Sets [value] only when it differs: an unchanged setText still lays out and redraws the whole screen. */
fun TextView.put(value: CharSequence) { if (text.toString() != value.toString()) text = value }

/**
 * Look of the main camp screen (design A1·B1): the night globe fills the screen and large car-sized
 * text, buttons and an icon menu sit on top. Only views and styling live here; [CampActivity] wires behaviour.
 */
internal class CampScreen(private val context: Context) {
    enum class Style { PRIMARY, SECONDARY, HIGHLIGHT }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun dpf(value: Float) = value * density

    /** Under 780 dp wide the menu drops below the player block and the screen scrolls. */
    val wide = context.resources.configuration.screenWidthDp >= WIDE_DP

    val root = FrameLayout(context)
    val globe = GlobeView(context)
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(20), dp(32), dp(12)) }
    val lock = text(context.getString(R.string.driving_lock), 28f).apply {
        gravity = Gravity.CENTER; setPadding(dp(60), dp(40), dp(60), dp(40)); setBackgroundColor(LOCK_DIM)
    }
    val account = CarButton(context).apply {
        setText(R.string.link_phone); textSize = 24f; isAllCaps = false; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        setTextColor(CREAM); shade(this); flat(this)
        background = surface(context, Style.SECONDARY, dpf(38f), fill = CHIP); setPadding(dp(28), 0, dp(28), 0)
    }
    /** Language (2026-10-21): a globe and the current language in itself; the activity hides it below Android 13. */
    val language = CarButton(context).apply {
        textSize = 24f; isAllCaps = false; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        setTextColor(CREAM); shade(this); flat(this)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_globe, 0, 0, 0)
        compoundDrawablePadding = dp(10); compoundDrawableTintList = ColorStateList.valueOf(CREAM)
        background = surface(context, Style.SECONDARY, dpf(38f), fill = CHIP); setPadding(dp(22), 0, dp(26), 0)
    }
    /** Voice guide switch (2026-10-21): the activity sets the speaker icon (on/off) and its spoken state. */
    val voice = CarButton(context).apply {
        setText(R.string.voice_guide); textSize = 24f; isAllCaps = false; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        setTextColor(CREAM); shade(this); flat(this)
        compoundDrawablePadding = dp(10); compoundDrawableTintList = ColorStateList.valueOf(CREAM)
        background = surface(context, Style.SECONDARY, dpf(38f), fill = CHIP); setPadding(dp(22), 0, dp(26), 0)
    }
    /** Talk (2026-10-21): the mic alone; the activity shows it only on Pleos cars that hear the car's language. */
    val talk = CarButton(context).apply {
        contentDescription = context.getString(R.string.talk); isVisible = false
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(CREAM); shade(this); flat(this)
        background = surface(context, Style.SECONDARY, dpf(38f), fill = CHIP); setPadding(dp(22), 0, dp(22), 0)
    }
    val status = text("", UiScale.readable(20f, 12f), MUTED).apply { gravity = Gravity.END; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val title = text(context.getString(R.string.welcome), 40f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    /** The place line: a flag and "country · capital" while a station plays, the welcome caption otherwise. */
    val country = text(context.getString(R.string.camp_caption), 24f, MUTED).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = (10 * context.resources.displayMetrics.density).toInt()
    }
    val journey = text("", 24f, GOLD).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    /** Today's voyage (2026-10-22), once parked after a drive: "오늘의 항해 · 7개 나라 · 가장 먼 곳 나미비아 10,499km". */
    val voyage = text("", 24f, CREAM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val menu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val play = button(R.string.play, Style.PRIMARY)
    val next = button(R.string.next, Style.SECONDARY)
    val favorites = button(R.string.favorites, Style.SECONDARY, R.drawable.ic_star)
    val countries = button(R.string.countries, Style.SECONDARY, R.drawable.ic_globe)
    val camp = button(R.string.camp_mode, Style.HIGHLIGHT, R.drawable.ic_tent)

    init {
        // Empty lines collapse so the player block hugs the buttons instead of leaving gaps.
        for (line in listOf(status, journey, voyage)) {
            line.isVisible = false
            line.doAfterTextChanged { line.isVisible = !it.isNullOrEmpty() }
        }
        root.addView(globe, FrameLayout.LayoutParams(-1, -1))
        root.addView(ReadingShade(context), FrameLayout.LayoutParams(-1, -1))
        // A low screen (e.g. 1920×720 at 1.33) overflows: keep the scroll bar on screen whenever there is more below.
        val scroll = ScrollView(context).apply { isFillViewport = true; isScrollbarFadingEnabled = false }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(lock, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }

        top.addView(text("VOYADIO", 24f, GOLD))
        val corner = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        corner.addView(talk, LinearLayout.LayoutParams(-2, dp(BUTTON_DP)).apply { marginEnd = dp(12) })
        corner.addView(voice, LinearLayout.LayoutParams(-2, dp(BUTTON_DP)).apply { marginEnd = dp(12) })
        corner.addView(language, LinearLayout.LayoutParams(-2, dp(BUTTON_DP)).apply { marginEnd = dp(12) })
        corner.addView(account, LinearLayout.LayoutParams(-2, dp(BUTTON_DP)))
        top.addView(corner, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        content.addView(top)
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        content.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f)) // pushes the rest to the bottom

        val player = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        info.addView(title); info.addView(country); info.addView(journey); info.addView(voyage)
        player.addView(info)
        // Equal halves (PO 2026-10-22: play/save and next the same size) so both always fit; capped at 280 + 280 dp on roomy screens.
        val controls = CappedRow(context, dp(PLAY_DP + GAP_DP + NEXT_DP))
        controls.addView(play, LinearLayout.LayoutParams(0, dp(BUTTON_DP), PLAY_DP.toFloat()))
        controls.addView(next, LinearLayout.LayoutParams(0, dp(BUTTON_DP), NEXT_DP.toFloat()).apply { marginStart = dp(GAP_DP) })
        player.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        for ((index, item) in listOf(favorites, countries, camp).withIndex()) {
            menu.addView(item, LinearLayout.LayoutParams(-1, dp(BUTTON_DP)).apply { if (index > 0) topMargin = dp(10) })
        }
        val bottom = LinearLayout(context)
        if (wide) {
            bottom.gravity = Gravity.BOTTOM
            bottom.addView(player, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(24) })
            bottom.addView(menu, LinearLayout.LayoutParams(dp(MENU_DP), -2))
        } else {
            bottom.orientation = LinearLayout.VERTICAL
            bottom.addView(player, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            bottom.addView(menu, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        }
        content.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        content.addView(text(context.getString(R.string.map_credit), UiScale.readable(14f, 11f), MUTED).apply { gravity = Gravity.END; maxLines = 2 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> globe.clear = clearArea() }
    }

    /**
     * Where a zoomed route can show (2026-10-15): below the top row, above the station text, left of the menu.
     * Only the wide layout has such a gap; the narrow one scrolls, so the route uses the whole screen there.
     */
    private fun clearArea(): RectF? {
        if (!wide || globe.width == 0) return null
        val origin = IntArray(2).also(globe::getLocationInWindow)
        fun at(view: View) = IntArray(2).also(view::getLocationInWindow)
        val gap = dp(16)
        val left = (at(content)[0] - origin[0] + content.paddingLeft).toFloat()
        val upper = (at(top)[1] - origin[1] + top.height + gap).toFloat()
        val lower = (at(info)[1] - origin[1] - gap).toFloat()
        val right = (at(menu)[0] - origin[0] - gap).toFloat()
        if (lower - upper < dp(MIN_CLEAR_DP) || right - left < dp(MIN_CLEAR_DP)) return null
        return RectF(left, upper, right, lower)
    }

    /** Text drawn over the globe; [shadow] keeps it readable on bright land and city lights. */
    fun text(value: CharSequence, size: Float, color: Int = CREAM, shadow: Boolean = true) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (shadow) shade(this)
    }

    private fun button(label: Int, style: Style, icon: Int? = null) = button(context, context.getString(label), style, icon)

    /** A disabled button fades so it reads as unavailable at a glance. */
    class CarButton(context: Context) : Button(context) {
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    /** A row never wider than [cap]; its weighted children share whatever width is left. */
    private class CappedRow(context: Context, private val cap: Int) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val capped = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
            super.onMeasure(MeasureSpec.makeMeasureSpec(capped, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }

    /** Darkens the bottom 60 % and the right 40 % so the text and menu stay legible over the globe. */
    private class ReadingShade(context: Context) : View(context) {
        private val bottom = Paint()
        private val right = Paint()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            bottom.shader = LinearGradient(0f, h * 0.4f, 0f, h.toFloat(), CLEAR, SHADE_BOTTOM, Shader.TileMode.CLAMP)
            right.shader = LinearGradient(w * 0.6f, 0f, w.toFloat(), 0f, CLEAR, SHADE_RIGHT, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, height * 0.4f, width.toFloat(), height.toFloat(), bottom)
            canvas.drawRect(width * 0.6f, 0f, width.toFloat(), height.toFloat(), right)
        }
    }

    companion object {
        /**
         * The car button shared by the main screen and [CarSheet]: 24 sp, no platform minimum size,
         * gold (primary), outlined (secondary) or gold-tinted (highlight). [shadow] is for text drawn over the globe.
         */
        fun button(context: Context, label: CharSequence, style: Style, icon: Int? = null, shadow: Boolean = true): Button =
            CarButton(context).apply {
                text = label; textSize = 24f; isAllCaps = false; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                restyle(this, style, shadow)
                flat(this)
                if (icon != null) {
                    val ink = ink(style)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(px(context, 22), 0, px(context, 22), 0)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                    compoundDrawablePadding = px(context, 14); compoundDrawableTintList = ColorStateList.valueOf(ink)
                } else {
                    gravity = Gravity.CENTER; setPadding(px(context, 12), 0, px(context, 12), 0)
                }
            }

        /** (Re)colours a button for [style] — a choice cell turns gold when picked. */
        fun restyle(view: TextView, style: Style, shadow: Boolean = true) {
            view.setTextColor(ink(style))
            if (shadow && style != Style.PRIMARY) shade(view) else view.setShadowLayer(0f, 0f, 0f, 0) // dark text on gold needs no halo
            view.background = surface(view.context, style, 16f * density(view.context))
        }

        private fun ink(style: Style) = when (style) { Style.PRIMARY -> INK; Style.SECONDARY -> CREAM; Style.HIGHLIGHT -> GOLD }

        fun shade(view: TextView) {
            val density = density(view.context)
            view.setShadowLayer(8f * density, 0f, density, SHADOW)
        }

        /** Drops the platform button's minimum size and lift so the 76 dp layout sizes are exact. */
        fun flat(view: Button) {
            view.minHeight = 0; view.minimumHeight = 0; view.minWidth = 0; view.minimumWidth = 0
            view.stateListAnimator = null
        }

        fun surface(context: Context, style: Style, radius: Float, fill: Int? = null): Drawable {
            val edge = (1.5f * density(context) + 0.5f).toInt().coerceAtLeast(1)
            val shape = GradientDrawable().apply {
                cornerRadius = radius
                when (style) {
                    Style.PRIMARY -> setColor(GOLD)
                    Style.SECONDARY -> { setColor(fill ?: PANEL); setStroke(edge, EDGE) }
                    Style.HIGHLIGHT -> { setColor(GOLD_TINT); setStroke(edge, GOLD) }
                }
            }
            return RippleDrawable(ColorStateList.valueOf(PRESS), shape, null)
        }

        private fun density(context: Context) = context.resources.displayMetrics.density
        private fun px(context: Context, value: Int) = (value * density(context)).toInt()

        const val WIDE_DP = 780
        const val BUTTON_DP = 76
        private const val MIN_CLEAR_DP = 120 // a smaller gap is not worth fitting a route into
        const val MENU_DP = 380
        const val PLAY_DP = 280
        const val NEXT_DP = 280
        const val GAP_DP = 12
        const val DISABLED_ALPHA = 0.4f
        val CREAM = Color.rgb(244, 232, 208)
        val MUTED = Color.rgb(168, 191, 183)
        val GOLD = Color.rgb(232, 195, 135)
        val INK = Color.rgb(22, 45, 41)              // text on the gold button
        val PANEL = Color.argb(235, 35, 62, 57)      // #233E39 92%
        val CHIP = Color.argb(217, 35, 62, 57)       // #233E39 85%
        val EDGE = Color.argb(179, 168, 191, 183)    // #A8BFB7 70%
        val GOLD_TINT = Color.argb(41, 232, 195, 135) // gold 16%
        val PRESS = Color.argb(64, 244, 232, 208)
        val SHADOW = Color.argb(230, 0, 0, 0)        // #E6000000
        val LOCK_DIM = Color.argb(166, 1, 4, 6)
        val CLEAR = Color.argb(0, 1, 4, 6)
        val SHADE_BOTTOM = Color.argb(217, 1, 4, 6)  // #D9010406
        val SHADE_RIGHT = Color.argb(191, 1, 4, 6)   // #BF010406
    }
}
