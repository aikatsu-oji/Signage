package jp.signage.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

internal const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** 天気予報の各画面に共通する背景・見出し・文字の部品 */
abstract class PanelView(context: Context) : LinearLayout(context) {
    protected companion object {
        const val WHITE = Color.WHITE
        const val DIM = 0xB3FFFFFF.toInt()
        const val HOT = 0xFFFF8A80.toInt()
        const val COLD = 0xFF82B1FF.toInt()
        const val CARD = 0x26FFFFFF
        const val CELL = 0x1AFFFFFF
    }

    /** 画面の短辺を基準にした文字・余白の単位（px） */
    protected var u = 30f
    /** 分割画面の小さな区画など、項目数を減らして表示する */
    protected var compact = false

    init {
        orientation = VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1565C0.toInt(), 0xFF0D2A5C.toInt()),
        )
    }

    /** 表示するデータがあるか */
    protected abstract val hasData: Boolean

    /** 中身を組み立てる（removeAllViews と u の計算は済んだ状態で呼ばれる） */
    protected abstract fun render(landscape: Boolean)

    protected fun rerender() {
        if (!hasData) return
        removeAllViews()
        val dm = resources.displayMetrics
        val w = if (width > 0) width else dm.widthPixels
        val h = if (height > 0) height else dm.heightPixels
        val landscape = w > h
        compact = maxOf(w, h) < 1300
        u = min(w, h) / (if (landscape) 34f else 30f)
        val pad = (u * 1.2f).toInt()
        setPadding(pad, pad, pad, pad)
        render(landscape)
    }

    /** 画面の回転などでサイズが変わったら描き直す */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) post { rerender() }
    }

    /** 見出し（横向きは題名と時計を1行に、縦向きは2行に）と発表時刻 */
    protected fun addHeader(title: String, report: LocalDateTime, stale: Boolean, landscape: Boolean, extra: String? = null) {
        val clockView = text(clock(LocalDateTime.now()), 1.6f, bold = true)
        // 題名が1行に収まらないときは文字を縮める（縮めても入らない分は省略）
        val titleView = text(title, 2.2f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val panel = this@PanelView
            val w = if (panel.width > 0) panel.width else resources.displayMetrics.widthPixels
            var avail = w - panel.paddingLeft - panel.paddingRight.toFloat()
            if (landscape) avail -= clockView.paint.measureText(clockView.text.toString()) + u
            val need = paint.measureText(title)
            if (need > avail && avail > 0) setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * maxOf(0.6f, avail / need))
        }
        if (landscape) {
            val header = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
            header.addView(titleView, LayoutParams(0, WRAP, 1f))
            header.addView(clockView.apply { setPadding(u.toInt(), 0, 0, 0) })
            addView(header)
        } else {
            addView(titleView)
            addView(clockView.apply { setPadding(0, gap(), 0, 0) })
        }
        val note = "${report.monthValue}月${report.dayOfMonth}日 ${report.hour}時 気象庁発表" +
            (extra?.let { "　$it" } ?: "") +
            if (stale) "（通信できないため前回取得した予報を表示中）" else ""
        addView(text(note, 0.9f, color = DIM))
    }

    protected fun addFooter() {
        addView(text("出典：気象庁ホームページ", 0.8f, color = DIM).apply { gravity = Gravity.END })
    }

    /** 天気アイコン（主な天気を大きく、時々・後などの天気を小さく右に添える） */
    protected fun icon(main: String, sub: String?, size: Float): TextView {
        val s = SpannableStringBuilder(main)
        if (sub != null) {
            val start = s.length
            s.append(sub)
            s.setSpan(RelativeSizeSpan(0.55f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text("", size).apply {
            setText(s)
            gravity = Gravity.CENTER
        }
    }

    protected fun text(s: String, size: Float, bold: Boolean = false, color: Int = WHITE) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u * size)
        includeFontPadding = false
        setShadowLayer(u * 0.15f, 0f, u * 0.05f, 0x66000000)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    protected fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = u * 0.8f
    }

    protected fun gap() = (u * 0.3f).toInt()

    protected fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)

    protected fun dateLabel(date: LocalDate) = "${date.monthValue}/${date.dayOfMonth}(${weekday(date)})"

    protected fun dayColor(date: LocalDate, default: Int) = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> COLD
        DayOfWeek.SUNDAY -> HOT
        else -> default
    }

    private fun clock(t: LocalDateTime) =
        "${t.monthValue}月${t.dayOfMonth}日(${weekday(t.toLocalDate())}) ${t.hour}:%02d".format(t.minute)
}
