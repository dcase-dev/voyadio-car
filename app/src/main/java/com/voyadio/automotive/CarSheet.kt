package com.voyadio.automotive

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.voyadio.automotive.CampScreen.Style

/**
 * Car-sized dialog card (design C): dimmed screen, one dark rounded card in the middle with a 32 sp title,
 * 24 sp body or a 76 dp list, and right-aligned 76 dp buttons styled like the main screen.
 * The card never exceeds the screen minus 32 dp on every side; the body or list scrolls inside it.
 *
 * Build it, then [show] it; the caller keeps the returned [Dialog] to close it (driving, onStop, one at a time).
 */
internal class CarSheet(private val context: Context, widthDp: Int = WIDTH_DP) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    // Instance colors keep the companion free of Android calls, so [width] runs in plain JVM tests.
    private val cardColor = Color.argb(250, 14, 31, 32)      // #0E1F20 98%
    private val borderColor = Color.argb(115, 168, 191, 183) // #A8BFB7 45%
    /** Search well: darker than the card and the rows, so it reads as "type here", not "tap me". */
    private val inset = Color.rgb(5, 13, 15) // #050D0F
    private val rimColor = Color.argb(90, 168, 191, 183) // muted 35%

    private val edge = (1.5f * density + 0.5f).toInt().coerceAtLeast(1)
    private val surface = GradientDrawable().apply {
        cornerRadius = RADIUS_DP * density; setColor(cardColor); setStroke(edge, borderColor)
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(PAD_X_DP), dp(PAD_Y_DP), dp(PAD_X_DP), dp(PAD_Y_DP))
        background = surface
        elevation = ELEVATION_DP * density
    }
    private val heading = TextView(context).apply {
        textSize = 32f; setTextColor(CampScreen.CREAM); setTypeface(typeface, Typeface.BOLD)
        isVisible = false; ViewCompat.setAccessibilityHeading(this, true)
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { isScrollbarFadingEnabled = false; isVisible = false; addView(body) }
    private val buttons = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; isVisible = false }
    private var list: ListView? = null
    private var searching = false
    private var keyboardUp = false
    private var sectionGap = SECTION_GAP_DP
    private var rowGap = GAP_DP
    /** Rows side by side in [groups]: 2 on a low, wide screen (a phone in landscape, a 1920×720 head unit). */
    private val columns = context.resources.configuration.let { columns(it.screenWidthDp, it.screenHeightDp) }

    private val dialog = object : Dialog(context, R.style.CarSheet) {
        private var downOutside = false

        // A tap on the dim around the card closes it, like a platform dialog (the card itself never closes it).
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Window coordinates on both sides (the card's rect is relative to this window's root).
            val outside = !card.isShown || !Rect().also(card::getGlobalVisibleRect).contains(event.x.toInt(), event.y.toInt())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downOutside = outside
                MotionEvent.ACTION_UP -> if (downOutside && outside) { downOutside = false; cancel(); return true }
                MotionEvent.ACTION_CANCEL -> downOutside = false
            }
            return super.onTouchEvent(event)
        }
    }

    init {
        card.addView(heading, LinearLayout.LayoutParams(-1, -2))
        // Height 0 + weight: the body takes what the title and buttons leave, so a long list scrolls inside the card.
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(GAP_DP) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ACTIONS_GAP_DP) })
        val root = FrameLayout(context)
        root.addView(card, FrameLayout.LayoutParams(dp(width(widthDp, context.resources.configuration.screenWidthDp)), -2, Gravity.CENTER)
            .apply { setMargins(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP)) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(DIM)
            UiScale.hideStatusBar(this, context) // the sheet takes focus; without this the status bar comes back
        }
    }

    /**
     * Tighter vertical spacing for a tall form (camp start) so title → choices → buttons fit a low car screen
     * (about 628 dp) without scrolling; 76 dp cells and 24 sp text stay. Call before adding content.
     */
    fun compact() = apply {
        card.setPadding(dp(PAD_X_DP), dp(COMPACT_PAD_Y_DP), dp(PAD_X_DP), dp(COMPACT_PAD_Y_DP))
        (buttons.layoutParams as LinearLayout.LayoutParams).topMargin = dp(COMPACT_ACTIONS_GAP_DP)
        sectionGap = COMPACT_SECTION_GAP_DP; rowGap = COMPACT_ROW_GAP_DP
    }

    fun title(text: CharSequence) = apply { heading.text = text; heading.isVisible = true; windowTitle(text) }

    /** Window title only, read out by TalkBack when the window opens; for a card whose headline is custom content. */
    fun windowTitle(text: CharSequence) = apply { dialog.setTitle(text) }
    fun title(id: Int) = title(context.getText(id))

    /** Body text (24 sp, cream); scrolls when long. */
    fun message(text: CharSequence) = content(TextView(context).apply {
        this.text = text; textSize = 24f; setTextColor(CampScreen.CREAM); setLineSpacing(0f, 1.15f)
    })
    fun message(id: Int) = message(context.getText(id))

    /** Any custom view, stacked in the scrolling body [gapDp] below the one before. */
    fun content(view: View, gapDp: Int = GAP_DP) = apply {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { if (body.isNotEmpty()) topMargin = dp(gapDp) })
        scroll.isVisible = true
    }

    /** [view] as a new group, a section gap below the one before. */
    fun section(view: View) = content(view, sectionGap)

    /** A 24 sp muted caption that opens a group (e.g. above a row of [choices]). */
    fun caption(id: Int) = section(TextView(context).apply {
        setText(id); textSize = 24f; setTextColor(CampScreen.MUTED)
    })

    /**
     * One row of equal-width 76 dp choices; [selected] is gold. Tapping another cell moves the gold and calls [onChange]
     * (the card stays open). [descriptions] replace the short labels for TalkBack, e.g. "60%" → "Keep 60%".
     */
    fun choices(labels: List<CharSequence>, selected: Int, descriptions: List<CharSequence>? = null, onChange: (Int) -> Unit) = apply {
        val row = LinearLayout(context)
        val cells = labels.mapIndexed { index, label ->
            CampScreen.button(context, label, Style.SECONDARY, shadow = false).also { cell ->
                descriptions?.getOrNull(index)?.let { cell.contentDescription = it }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(CampScreen.BUTTON_DP), 1f).apply { if (index > 0) marginStart = dp(GAP_DP) })
            }
        }
        fun mark(chosen: Int) = cells.forEachIndexed { index, cell ->
            CampScreen.restyle(cell, if (index == chosen) Style.PRIMARY else Style.SECONDARY, shadow = false)
            cell.isSelected = index == chosen // TalkBack says "selected"
        }
        cells.forEachIndexed { index, cell -> cell.setOnClickListener { mark(index); onChange(index) } }
        mark(selected)
        content(row, rowGap)
    }

    /**
     * A row of [groups]: a name, an optional muted [detail] (e.g. "24 stations") and a chevron; [key] comes back on pick.
     * [flag] (a country code) puts that country's flag in front of the detail.
     */
    class Entry(val label: CharSequence, val detail: CharSequence? = null, val key: Any? = null, val flag: String? = null)

    /**
     * Rows under muted headings (a null heading shows none). Picking a row closes the card, then calls [onPick].
     * Headings hide while a [search] keyboard is up, so the first result keeps the little room left.
     */
    fun groups(groups: List<Pair<CharSequence?, List<Entry>>>, onPick: (Entry) -> Unit) = apply {
        // Two columns get a wider card, so each row keeps about the width one row had.
        if (columns > 1) card.layoutParams = card.layoutParams.apply {
            width = dp(width(WIDE_WIDTH_DP, context.resources.configuration.screenWidthDp))
        }
        listView().adapter = Groups(groups) { entry -> dialog.dismiss(); onPick(entry) }
    }

    /**
     * A tall list of 76 dp choices (outlined; [selected] is gold). Picking one closes the card, then calls [onPick].
     * Rows are recycled, so a country with hundreds of stations opens as fast as a short list.
     */
    fun list(items: List<CharSequence>, selected: Int? = null, onPick: (Int) -> Unit) = apply {
        val view = listView()
        view.adapter = Rows(items, selected) { index -> dialog.dismiss(); onPick(index) }
        if (selected != null) view.setSelection(selected)
    }

    private fun listView(): ListView = list ?: ListView(context).also { created ->
            created.divider = Color.TRANSPARENT.toDrawable(); created.dividerHeight = dp(GAP_DP)
            created.selector = Color.TRANSPARENT.toDrawable(); created.itemsCanFocus = true
            created.isScrollbarFadingEnabled = false; created.isVerticalScrollBarEnabled = true
            created.overScrollMode = View.OVER_SCROLL_NEVER
            card.addView(created, card.indexOfChild(buttons), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(GAP_DP) })
            list = created
        }

    /**
     * A 76 dp search field under the title (parked only — the screen is closed while driving; voice search covers
     * driving). Every change calls [onQuery], which typically re-fills the [list] below.
     */
    fun search(hint: CharSequence, onQuery: (String) -> Unit) = apply {
        // Looks nothing like a row (2026-10-13 PO): a dark recessed pill with a magnifier, gold rim while typing,
        // and a clear button — rows are raised tiles with a count and a chevron.
        val well = GradientDrawable().apply { cornerRadius = CampScreen.BUTTON_DP / 2f * density; setColor(inset) }
        fun rim(focused: Boolean) = well.setStroke(if (focused) dp(2) else edge, if (focused) CampScreen.GOLD else rimColor)
        rim(false)
        val pill = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; background = well; setPadding(dp(24), 0, dp(8), 0)
        }
        val clear = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close); background = null; isVisible = false
            contentDescription = context.getString(R.string.search_clear)
        }
        val field = EditText(context).apply {
            this.hint = hint; textSize = 24f; setTextColor(CampScreen.CREAM); setHintTextColor(CampScreen.MUTED)
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null; setPadding(dp(16), 0, dp(8), 0)
            doAfterTextChanged { text -> clear.isVisible = !text.isNullOrEmpty(); onQuery(text?.toString().orEmpty()) }
            setOnFocusChangeListener { _, focused -> rim(focused) }
            setOnEditorActionListener { view, _, _ ->
                context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus(); true
            }
        }
        clear.setOnClickListener { field.text.clear() }
        pill.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        pill.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
        pill.addView(clear, LinearLayout.LayoutParams(dp(CampScreen.BUTTON_DP - 16), dp(CampScreen.BUTTON_DP - 16)))
        searching = true
        // The dialog window does not report keyboard insets on AAOS (emulator check), so measure instead: the keyboard
        // is up when the visible part of the window drops well below the tallest it has been (the system bars are
        // always outside it, so the screen height is the wrong yardstick — 2026-10-13 emulator check).
        var tallest = 0
        card.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect().also(card.rootView::getWindowVisibleDisplayFrame).height()
            tallest = maxOf(tallest, visible)
            val up = visible < tallest * KEYBOARD_SHARE
            if (up != keyboardUp) { keyboardUp = up; placeButtons(); (list?.adapter as? Groups)?.refresh() }
        }
        card.addView(pill, card.indexOfChild(heading) + 1, LinearLayout.LayoutParams(-1, dp(CampScreen.BUTTON_DP)).apply { topMargin = dp(GAP_DP) })
        // The keyboard shrinks the window instead of covering the list it filters.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    fun search(id: Int, onQuery: (String) -> Unit) = search(context.getText(id), onQuery)

    /**
     * A right-aligned 76 dp button (at least 160 dp wide). [primary] is the gold one;
     * the card closes before [onClick] runs.
     */
    fun action(label: CharSequence, primary: Boolean = false, onClick: () -> Unit = {}) = apply {
        val button = CampScreen.button(context, label, if (primary) Style.PRIMARY else Style.SECONDARY, shadow = false).apply {
            minWidth = dp(ACTION_MIN_DP); setPadding(dp(28), 0, dp(28), 0)
            setOnClickListener { dialog.dismiss(); onClick() }
        }
        buttons.addView(button, LinearLayout.LayoutParams(-2, dp(CampScreen.BUTTON_DP)).apply {
            if (buttons.isNotEmpty()) marginStart = dp(12)
        })
        placeButtons()
    }

    /**
     * The action row hides while a [search] keyboard is up: on a low car screen the keyboard leaves room under the field
     * for only a row or two of results, and they matter more than "Close" (2026-10-13 emulator check).
     */
    private fun placeButtons() { buttons.isVisible = buttons.isNotEmpty() && !(searching && keyboardUp) }
    fun action(id: Int, primary: Boolean = false, onClick: () -> Unit = {}) = action(context.getText(id), primary, onClick)

    /** Card outline colour (the morning report uses gold). */
    fun border(color: Int) = apply { surface.setStroke(edge, color) }

    /** Keeps the card out of screenshots and casts (account codes). */
    fun secure() = apply { dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }

    fun onDismiss(action: () -> Unit) = apply { dialog.setOnDismissListener { action() } }

    fun show(): Dialog = dialog.also { it.show() }

    private inner class Groups(
        private val groups: List<Pair<CharSequence?, List<Entry>>>, private val pick: (Entry) -> Unit,
    ) : BaseAdapter() {
        private var cells: List<Any> = flatten()

        /** Headings, then the rows — one [Entry] per cell, or [columns] of them per cell on a low, wide screen. */
        private fun flatten(): List<Any> = groups.flatMap { (heading, rows) ->
            listOfNotNull(heading?.takeUnless { keyboardUp }) + if (columns > 1) rows.chunked(columns) else rows
        }
        fun refresh() { cells = flatten(); notifyDataSetChanged() }

        override fun getCount() = cells.size
        override fun getItem(position: Int) = cells[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (cells[position]) { is Entry -> 1; is List<*> -> 2; else -> 0 }
        override fun isEnabled(position: Int) = cells[position] !is CharSequence
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = cells[position]
            if (cell is List<*>) {
                // A line of [columns] rows; a short last line keeps its empty slot so the rows stay aligned.
                val line = (convertView as? LinearLayout)?.takeIf { it.tag == LINE } ?: LinearLayout(context).apply {
                    tag = LINE
                    layoutParams = AbsListView.LayoutParams(-1, dp(CampScreen.BUTTON_DP))
                    repeat(columns) { i -> addView(entryRow(), LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(GAP_DP) }) }
                }
                for (i in 0 until columns) {
                    val row = line.getChildAt(i) as LinearLayout
                    val entry = cell.getOrNull(i) as Entry?
                    row.visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
                    if (entry != null) bind(row, entry)
                }
                return line
            }
            if (cell !is Entry) return ((convertView as? TextView) ?: TextView(context).apply {
                textSize = 24f; setTextColor(CampScreen.MUTED); setPadding(dp(8), dp(8), dp(8), 0)
                ViewCompat.setAccessibilityHeading(this, true)
            }).apply { text = cell as CharSequence }
            val row = (convertView as? LinearLayout) ?: entryRow().apply { layoutParams = AbsListView.LayoutParams(-1, dp(CampScreen.BUTTON_DP)) }
            bind(row, cell)
            return row
        }

        /** A 76 dp row: name, optional muted detail, chevron. */
        private fun entryRow() = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), 0, dp(16), 0)
            background = CampScreen.surface(context, Style.SECONDARY, 16 * density)
            addView(TextView(context).apply {
                textSize = 24f; setTextColor(CampScreen.CREAM); setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply { textSize = 24f; setTextColor(CampScreen.MUTED); setSingleLine() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        }

        private fun bind(row: LinearLayout, entry: Entry) {
            (row.getChildAt(0) as TextView).text = entry.label
            (row.getChildAt(1) as TextView).apply {
                text = entry.detail; isVisible = entry.detail != null
                val flag = entry.flag?.let { Flags.drawable(context.resources, it, (textSize * 1.05f).toInt()) }
                setCompoundDrawablesRelative(flag, null, null, null)
                compoundDrawablePadding = dp(10)
            }
            row.setOnClickListener { pick(entry) }
        }
    }

    private inner class Rows(
        private val items: List<CharSequence>, private val selected: Int?, private val pick: (Int) -> Unit,
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == selected) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val chosen = position == selected
            val row = (convertView as? TextView) ?: CampScreen.button(context, "", if (chosen) Style.PRIMARY else Style.SECONDARY, shadow = false).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(dp(24), 0, dp(24), 0)
                layoutParams = AbsListView.LayoutParams(-1, dp(CampScreen.BUTTON_DP))
            }
            row.text = items[position]
            row.isSelected = chosen // TalkBack says "selected"
            row.setOnClickListener { pick(position) }
            return row
        }
    }

    companion object {
        const val WIDTH_DP = 860
        /** A two-column [groups] card. */
        const val WIDE_WIDTH_DP = 1240
        /** Below this height a list shows only two or three rows, so wide screens get two columns. */
        const val LOW_DP = 720
        const val TWO_COLUMN_MIN_WIDTH_DP = 1200
        private const val LINE = "line"
        const val MARGIN_DP = 32
        const val PAD_X_DP = 28
        const val PAD_Y_DP = 24
        const val RADIUS_DP = 20
        const val ELEVATION_DP = 24
        const val GAP_DP = 10
        const val SECTION_GAP_DP = 24
        const val ACTIONS_GAP_DP = 20
        // compact(): 20 + 12 + 8 + 16 dp save 52 dp on the camp start card (≈653 → ≈601 dp tall incl. margins).
        const val COMPACT_PAD_Y_DP = 20
        const val COMPACT_SECTION_GAP_DP = 12
        const val COMPACT_ROW_GAP_DP = 8
        const val COMPACT_ACTIONS_GAP_DP = 16
        const val ACTION_MIN_DP = 160
        const val DIM = 0.62f
        /** Visible window share under which a [search] keyboard counts as up. */
        private const val KEYBOARD_SHARE = 0.8f

        /** Card width: the requested width, but never wider than the screen minus a 32 dp margin each side. */
        fun width(requestedDp: Int, screenWidthDp: Int) = minOf(requestedDp, screenWidthDp - 2 * MARGIN_DP).coerceAtLeast(0)

        /** Two columns on a low, wide screen; one wherever the list has the height to scroll comfortably. */
        fun columns(screenWidthDp: Int, screenHeightDp: Int) =
            if (screenHeightDp in 1 until LOW_DP && screenWidthDp >= TWO_COLUMN_MIN_WIDTH_DP) 2 else 1
    }
}
