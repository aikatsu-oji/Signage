package jp.simplesignage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** 3時間ごとの天気（地域時系列予報）の全画面表示 */
class TimeSeriesView(context: Context) : PanelView(context) {
    private companion object {
        /** 24時間分 */
        const val SLOTS = 8
    }

    private var data: TimeSeriesData? = null
    private var cityName: String? = null
    private var areaName: String? = null
    override val hasData get() = data?.slots?.isNotEmpty() == true

    fun bind(d: TimeSeriesData, cityName: String? = null, areaName: String? = null) {
        data = d
        this.cityName = cityName
        this.areaName = areaName
        rerender()
    }

    override fun render(landscape: Boolean) {
        val d = data ?: return
        val slots = d.slots.take(if (compact) SLOTS / 2 else SLOTS)
        val place = cityName ?: d.pointName.ifEmpty { null }
        val title = "3時間ごとの天気" + (place?.let { "（$it）" } ?: "")
        // 天気は地域単位、気温は代表地点の値なので、それぞれの対象を併記する
        val extra = listOfNotNull(
            areaName?.let { "予報区域：$it" },
            d.pointName.ifEmpty { null }?.let { "気温：${it}の予報" },
        ).joinToString("　").ifEmpty { null }
        addHeader(title, d.reportTime, d.stale, landscape, extra)

        if (landscape) {
            val row = LinearLayout(context)
            slots.forEachIndexed { i, slot ->
                val lp = LayoutParams(0, MATCH, 1f).apply { setMargins(gap(), gap(), gap(), gap()) }
                row.addView(column(slot, showDate = i == 0 || slot.time.toLocalDate() != slots[i - 1].time.toLocalDate()), lp)
            }
            addView(row, LayoutParams(MATCH, 0, 2.4f))
            addView(text("気温の変化", 0.9f, color = DIM).apply { setPadding(gap(), gap(), 0, 0) })
            addView(TempGraph(slots.map { it.temp?.toFloatOrNull() }), LayoutParams(MATCH, 0, 1f))
        } else {
            val list = LinearLayout(context).apply { orientation = VERTICAL }
            slots.forEachIndexed { i, slot ->
                val lp = LayoutParams(MATCH, 0, 1f).apply { setMargins(gap(), gap(), gap(), gap()) }
                list.addView(row(slot, showDate = i == 0 || slot.time.toLocalDate() != slots[i - 1].time.toLocalDate()), lp)
            }
            addView(list, LayoutParams(MATCH, 0, 1f))
        }
        addFooter()
    }

    private fun timeLabel(slot: TimeSlot) = "${slot.time.hour}時"

    private fun wind(slot: TimeSlot) =
        listOfNotNull(slot.windDir, slot.windRange?.let { "${it}m/s" }).joinToString(" ")

    private fun slotIcon(slot: TimeSlot, size: Float) =
        Weather.textIcon(slot.weather, slot.time.hour).let { (main, sub) -> icon(main, sub, size) }

    /** 横向き：1コマを縦長のカードで */
    private fun column(slot: TimeSlot, showDate: Boolean): View {
        val date = slot.time.toLocalDate()
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD)
            val p = gap()
            setPadding(p, p * 2, p, p * 2)
        }
        card.addView(text(if (showDate) dateLabel(date) else " ", 0.95f, bold = true, color = dayColor(date, DIM)))
        card.addView(text(timeLabel(slot), 1.5f, bold = true))
        card.addView(slotIcon(slot, 3.2f), LayoutParams(WRAP, 0, 1f))
        card.addView(text(slot.weather, 1.0f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(text(slot.temp?.let { "$it℃" } ?: "", 1.7f, bold = true))
        card.addView(text(wind(slot), 0.85f, color = DIM).apply { maxLines = 2 })
        for (i in 0 until card.childCount) (card.getChildAt(i) as? android.widget.TextView)?.gravity = Gravity.CENTER
        return card
    }

    /** 縦向き：1コマを横長の行で */
    private fun row(slot: TimeSlot, showDate: Boolean): View {
        val date = slot.time.toLocalDate()
        val r = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CELL)
            val p = (u * 0.6f).toInt()
            setPadding(p, 0, p, 0)
        }
        val time = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        if (showDate) time.addView(text(dateLabel(date), 0.85f, bold = true, color = dayColor(date, DIM)))
        time.addView(text(timeLabel(slot), 1.4f, bold = true))
        r.addView(time, LayoutParams((u * 5).toInt(), WRAP))
        r.addView(slotIcon(slot, 2.2f), LayoutParams((u * 4).toInt(), WRAP))
        r.addView(text(slot.weather, 1.1f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, WRAP, 1f))
        r.addView(text(slot.temp?.let { "$it℃" } ?: "", 1.6f, bold = true).apply {
            gravity = Gravity.END
        }, LayoutParams((u * 4).toInt(), WRAP))
        r.addView(text(wind(slot), 0.85f, color = DIM).apply {
            gravity = Gravity.END
            maxLines = 2
        }, LayoutParams((u * 5.5f).toInt(), WRAP))
        return r
    }

    /** 各コマの中央に点を打ち、線で結んだ気温グラフ（横向き用） */
    private inner class TempGraph(private val temps: List<Float?>) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HOT
            style = Paint.Style.STROKE
            strokeWidth = u * 0.15f
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            textSize = u * 0.9f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            val values = temps.filterNotNull()
            if (values.size < 2) return
            val lo = values.min()
            val hi = values.max()
            val top = label.textSize * 1.6f
            val bottom = height - u * 0.6f
            val colW = width.toFloat() / temps.size
            fun y(t: Float) = if (hi == lo) (top + bottom) / 2 else bottom - (t - lo) / (hi - lo) * (bottom - top)

            val path = Path()
            var started = false
            temps.forEachIndexed { i, t ->
                t ?: return@forEachIndexed
                val x = colW * (i + 0.5f)
                if (!started) path.moveTo(x, y(t)) else path.lineTo(x, y(t))
                started = true
            }
            canvas.drawPath(path, line)
            temps.forEachIndexed { i, t ->
                t ?: return@forEachIndexed
                val x = colW * (i + 0.5f)
                canvas.drawCircle(x, y(t), u * 0.28f, dot)
                canvas.drawText("${t.toInt()}°", x, y(t) - u * 0.6f, label)
            }
        }
    }
}
