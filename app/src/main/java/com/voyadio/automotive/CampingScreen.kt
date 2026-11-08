package com.voyadio.automotive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.core.view.isVisible
import com.voyadio.automotive.CampScreen.Style
import com.voyadio.automotive.camping.Mood

/**
 * 캠핑 화면(설계 2026-10-13 §2): 어둡고 움직이지 않는 밤 감상 화면. 뷰만 — 동작은 [CampActivity]가 잇는다.
 * 위: "캠핑"·배터리 한 줄·시계 / 가운데: 지금 방송 / 아래: 분위기 3개와 조작(멈춤·다른 방송·취침 예약·화면 끄기·나가기).
 */
internal class CampingScreen(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val wide = context.resources.configuration.screenWidthDp >= CampScreen.WIDE_DP

    // 반투명 막: 뒤의 멈춘 지구본(방송 나라)이 비치되 글자는 읽히게(2026-10-15). 화면 끄기 덮개는 따로 완전 검정.
    val root = FrameLayout(context).apply { setBackgroundColor(VEIL); isVisible = false }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(20), dp(32), dp(16)) }
    val battery = text("", 24f, CampScreen.MUTED).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val batteryRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; visibility = View.INVISIBLE }
    /** 밝기: 누를 때마다 15 → 40 → 70 → 100%(2026-10-15). 글자는 퍼센트 숫자뿐이라 50개 언어에 그대로 맞는다. */
    val brightness = button(R.string.camping_title, Style.SECONDARY, R.drawable.ic_sun)
    val clock = TextClock(context).apply { textSize = 32f; setTextColor(CampScreen.CREAM) }
    val title = text(context.getString(R.string.camping_start_hint), 40f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    val place = text("", 24f, CampScreen.MUTED).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = (10 * context.resources.displayMetrics.density).toInt()
    }
    /** 배터리 자동 보호 안내. 토스트는 차량 화면에서 보이지 않을 수 있어(2026-10-13 가상 차량) 화면 안에 남긴다. */
    private val notice = text("", 24f, CampScreen.GOLD).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isVisible = false }
    private val moodRow = LinearLayout(context)
    private val moodStatus = text("", 24f, CampScreen.MUTED)
    val retry = button(R.string.camping_retry, Style.SECONDARY)
    val play = button(R.string.play, Style.PRIMARY)
    val other = button(R.string.next, Style.SECONDARY)
    val screenOff = button(R.string.camping_screen_off, Style.SECONDARY)
    val exit = button(R.string.camping_exit, Style.SECONDARY)
    /** 화면 끄기: 검은 덮개. 아무 곳이나 누르면 돌아온다(동작은 [CampActivity]). */
    val blackout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK); isVisible = false; isClickable = true
        contentDescription = context.getString(R.string.camping_screen_off_hint)
    }
    private var shownMoods: List<String> = emptyList()
    private var moodButtons: List<Button> = emptyList()
    private var shownSelected: String? = null

    init {
        val scroll = ScrollView(context).apply { isFillViewport = true; isScrollbarFadingEnabled = false }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(context.getString(R.string.camping_title), 24f, CampScreen.GOLD))
        batteryRow.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_battery); imageTintList = ColorStateList.valueOf(CampScreen.MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        batteryRow.addView(battery, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        top.addView(batteryRow, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        top.addView(brightness, LinearLayout.LayoutParams(-2, dp(CampScreen.BUTTON_DP)).apply { marginStart = dp(16) })
        content.addView(top)
        content.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        content.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f)) // 나머지를 아래로 민다

        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(place, LinearLayout.LayoutParams(-1, -2))
        content.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(text(context.getString(R.string.camping_moods), 24f, CampScreen.GOLD),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        moodRow.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        content.addView(moodRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val status = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        status.addView(moodStatus, LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(retry, LinearLayout.LayoutParams(dp(RETRY_DP), dp(CampScreen.BUTTON_DP)).apply { marginStart = dp(12) })
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        moodStatus.isVisible = false; retry.isVisible = false

        val controls = LinearLayout(context).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        for ((index, button) in listOf(play, other, screenOff, exit).withIndex()) {
            controls.addView(button, if (wide) LinearLayout.LayoutParams(0, dp(CampScreen.BUTTON_DP), 1f).apply { if (index > 0) marginStart = dp(12) }
                else LinearLayout.LayoutParams(-1, dp(CampScreen.BUTTON_DP)).apply { if (index > 0) topMargin = dp(10) })
        }
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        blackout.addView(text(context.getString(R.string.camping_screen_off_hint), 24f, DIM).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(blackout, FrameLayout.LayoutParams(-1, -1))
    }

    /** 지금 방송 아래 금색 안내 한 줄; null이면 숨긴다. */
    fun notice(text: String?) { notice.put(text.orEmpty()); notice.isVisible = text != null }

    /** 배터리 한 줄; null이면(차가 잔량을 못 줌·권한 없음) 줄째 숨긴다. */
    // INVISIBLE(자리 유지): 배터리 줄이 없는 기기에서도 밝기 버튼이 오른쪽 끝에 머문다.
    fun battery(text: String?) { battery.put(text.orEmpty()); batteryRow.visibility = if (text != null) View.VISIBLE else View.INVISIBLE }

    /** 밝기 버튼: [label] = "15%", [spoken] = 화면 읽기용 문장. */
    fun brightness(label: String, spoken: String) { brightness.put(label); brightness.contentDescription = spoken }

    /**
     * 분위기 버튼 3개. 목록이 바뀔 때만 다시 만들고, 고른 분위기([selected])는 금색(PRIMARY).
     * [status]가 있으면(불러오는 중·실패) 버튼 대신 그 줄을, [retry]면 "다시 시도"도 보인다.
     */
    fun moods(moods: List<Mood>, selected: String?, status: String?, retry: Boolean, onPick: (Mood) -> Unit) {
        val ids = moods.map { it.id }
        val rebuilt = ids != shownMoods
        if (rebuilt) {
            shownMoods = ids
            moodRow.removeAllViews()
            moodButtons = moods.mapIndexed { index, mood ->
                CampScreen.button(context, mood.title(), Style.SECONDARY, shadow = false).also { button ->
                    button.setOnClickListener { onPick(mood) }
                    moodRow.addView(button, if (wide) LinearLayout.LayoutParams(0, dp(CampScreen.BUTTON_DP), 1f).apply { if (index > 0) marginStart = dp(12) }
                        else LinearLayout.LayoutParams(-1, dp(CampScreen.BUTTON_DP)).apply { if (index > 0) topMargin = dp(10) })
                }
            }
        }
        // Restyling sets a new background (a redraw), so only when the gold choice moves.
        if (rebuilt || selected != shownSelected) {
            shownSelected = selected
            moods.forEachIndexed { index, mood ->
                CampScreen.restyle(moodButtons[index], if (mood.id == selected) Style.PRIMARY else Style.SECONDARY, shadow = false)
            }
        }
        moodRow.isVisible = moods.isNotEmpty()
        moodStatus.put(status.orEmpty()); moodStatus.isVisible = status != null
        this.retry.isVisible = retry
    }

    private fun text(value: CharSequence, size: Float, color: Int = CampScreen.CREAM) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
    }

    private fun button(label: Int, style: Style, icon: Int? = null) =
        CampScreen.button(context, context.getString(label), style, icon, shadow = false)

    companion object {
        val VEIL = Color.argb(115, 2, 7, 10)         // #02070A 45%
        val DIM = Color.argb(140, 168, 191, 183)     // MUTED 55%: 덮개 위 안내는 아주 흐리게
        const val RETRY_DP = 220
    }
}
