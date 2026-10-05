package jp.signage.player

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** 地方ごとの一覧（日本地図のように、府県の天気を、位置に合わせてタイルで並べる） */
class RegionView(context: Context) : PanelView(context) {
    private var data: RegionPageData? = null
    override val hasData get() = data != null

    fun bind(d: RegionPageData) {
        data = d
        rerender()
    }

    override fun render(landscape: Boolean) {
        val d = data ?: return
        val day = if (d.dayIndex == 0) "きょう" else "あした"
        addHeader("${d.name}の天気（$day）", d.reportTime, d.stale, landscape, extra = "${dateLabel(d.date)}の予報")
        val map = TileMap(context)
        d.tiles.forEach { t -> map.addView(tile(t), TileMap.Params(t.tile.x, t.tile.y)) }
        addView(map, LayoutParams(MATCH, 0, 1f).apply { setMargins(0, gap(), 0, gap()) })
        addFooter()
    }

    private fun tile(t: TileForecast): View {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (u * 0.5f).toInt()
            setPadding(p + p / 2, p, p + p / 2, p)
        }
        box.addView(text(t.tile.label, 1.15f, bold = true).apply { maxLines = 1 })
        val day = t.day
        if (day == null) {
            box.background = rounded(0xFF78909C.toInt())
            box.addView(text("--", 1f))
            return box
        }
        val (main, sub) = Weather.icon(day.code)
        box.background = rounded(
            when (main) {
                "☀️", "🌙" -> 0xFFF57C00.toInt()
                "☔" -> 0xFF1565C0.toInt()
                "⛄" -> 0xFF4FA3D9.toInt()
                else -> 0xFF78909C.toInt()
            },
        )
        box.addView(icon(main, sub, if (compact) 1.7f else 2.3f))
        box.addView(text("${day.max?.let { "$it℃" } ?: "--"} / ${day.min?.let { "$it℃" } ?: "--"}", 0.95f).apply { maxLines = 1 })
        val pop = day.pops6h?.mapNotNull { it?.toIntOrNull() }?.maxOrNull()
        if (pop != null) box.addView(text("☂ $pop%", 0.8f, color = DIM))
        for (i in 0 until box.childCount) (box.getChildAt(i) as? android.widget.TextView)?.gravity = Gravity.CENTER
        return box
    }

    /** 子を、指定の位置（中心。縦横の％）に置く。はみ出さないように、端に寄せる */
    private class TileMap(context: Context) : ViewGroup(context) {
        class Params(val x: Float, val y: Float) : LayoutParams(WRAP, WRAP)

        override fun checkLayoutParams(p: LayoutParams?) = p is Params

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val h = MeasureSpec.getSize(heightSpec)
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val p = c.layoutParams as Params
                val left = (w * p.x / 100f - c.measuredWidth / 2f).toInt().coerceIn(0, maxOf(0, w - c.measuredWidth))
                val top = (h * p.y / 100f - c.measuredHeight / 2f).toInt().coerceIn(0, maxOf(0, h - c.measuredHeight))
                c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
            }
        }
    }
}
