package jp.signage.player

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 天気予報（今日・明日・週間）の全画面表示 */
class WeatherView(context: Context) : PanelView(context) {
    private var data: WeatherData? = null
    private var cityName: String? = null
    override val hasData get() = data != null

    /** cityName を指定すると見出しに市区町村名を出し、予報区域（地域名）を併記する */
    fun bind(d: WeatherData, cityName: String? = null) {
        data = d
        this.cityName = cityName
        rerender()
    }

    override fun render(landscape: Boolean) {
        val d = data ?: return
        val city = cityName
        if (city != null) {
            addHeader("${city}の天気", d.reportTime, d.stale, landscape, extra = "予報区域：${d.areaName}")
        } else {
            addHeader("${d.areaName}の天気", d.reportTime, d.stale, landscape)
        }

        // 今日・明日
        val cards = LinearLayout(context).apply { orientation = if (landscape) HORIZONTAL else VERTICAL }
        d.days.forEachIndexed { i, day ->
            val lp = if (landscape) LayoutParams(0, MATCH, 1f) else LayoutParams(MATCH, 0, 1f)
            lp.setMargins(gap(), gap(), gap(), gap())
            cards.addView(dayCard(day, if (i == 0) "今日" else "明日"), lp)
        }
        addView(cards, LayoutParams(MATCH, 0, if (landscape) 1.7f else 2.6f))

        // 週間予報
        if (d.week.isNotEmpty()) {
            addView(text("週間予報", 1.1f, bold = true).apply { setPadding(gap(), gap(), 0, 0) })
            val row = LinearLayout(context)
            d.week.take(if (compact) 4 else 7).forEach { day ->
                val lp = LayoutParams(0, MATCH, 1f).apply { setMargins(gap(), gap(), gap(), gap()) }
                row.addView(weekCell(day), lp)
            }
            addView(row, LayoutParams(MATCH, 0, 1f))
        }

        addFooter()
    }

    private fun dayCard(day: DayForecast, label: String): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD)
            val p = (u * 0.8f).toInt()
            setPadding(p, p, p, p)
        }
        card.addView(text("$label  ${dateLabel(day.date)}", 1.4f, bold = true, color = dayColor(day.date, WHITE)))

        val middle = LinearLayout(context).apply { gravity = Gravity.CENTER }
        middle.addView(codeIcon(day.code, if (compact) 3f else 5f))
        val temps = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding((u * 0.8f).toInt(), 0, 0, 0)
        }
        val tempSize = if (compact) 1.3f else 1.7f
        // 値が無い（気象庁の予報に含まれない）ときは、「--」を出さずに、その行を省く
        day.max?.let { temps.addView(text("最高 $it℃", tempSize, bold = true, color = HOT)) }
        day.min?.let { temps.addView(text("最低 $it℃", tempSize, bold = true, color = COLD)) }
        if (temps.childCount > 0) middle.addView(temps)
        card.addView(middle, LayoutParams(WRAP, 0, 1f))

        day.text?.let {
            card.addView(text(it, 1.2f).apply {
                gravity = Gravity.CENTER
                maxLines = if (compact) 1 else 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        // 小さな区画では降水確率の行を省き、アイコンと気温を優先する
        day.pops6h?.takeIf { !compact }?.let { pops ->
            card.addView(text("降水確率", 0.85f, color = DIM).apply { setPadding(0, gap(), 0, 0) })
            val row = LinearLayout(context)
            listOf("0-6時", "6-12時", "12-18時", "18-24時").forEachIndexed { i, h ->
                val cell = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                }
                cell.addView(text(h, 0.8f, color = DIM))
                cell.addView(text(pops[i]?.let { "$it%" } ?: "--", 1.3f, bold = true))
                row.addView(cell, LayoutParams(0, WRAP, 1f))
            }
            card.addView(row, LayoutParams(MATCH, WRAP))
        }
        return card
    }

    private fun weekCell(day: DayForecast): View {
        val cell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = rounded(CELL)
        }
        cell.addView(text(dateLabel(day.date), 0.95f, bold = true, color = dayColor(day.date, WHITE)))
        cell.addView(codeIcon(day.code, 2.4f))
        val temps = LinearLayout(context).apply { gravity = Gravity.CENTER }
        day.max?.let { temps.addView(text(it, 1.2f, bold = true, color = HOT)) }
        if (day.max != null && day.min != null) temps.addView(text(" / ", 1f, color = DIM))
        day.min?.let { temps.addView(text(it, 1.2f, bold = true, color = COLD)) }
        cell.addView(temps)
        cell.addView(text(day.pop?.let { "☂ $it%" } ?: "", 0.9f, color = DIM))
        for (i in 0 until cell.childCount) (cell.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
        return cell
    }

    private fun codeIcon(code: String, size: Float): TextView {
        val (main, sub) = Weather.icon(code)
        return icon(main, sub, size)
    }
}
