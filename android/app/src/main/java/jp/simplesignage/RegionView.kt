package jp.simplesignage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 地方ごとの一覧（日本地図の上に、府県の天気を、実際の位置にタイルで並べる） */
class RegionView(context: Context) : PanelView(context) {
    private var data: RegionPageData? = null
    private var region: Region? = null
    override val hasData get() = data != null

    fun bind(d: RegionPageData, region: Region?) {
        data = d
        this.region = region
        rerender()
    }

    override fun render(landscape: Boolean) {
        val d = data ?: return
        val day = if (d.dayIndex == 0) "きょう" else "あした"
        addHeader("${d.name}の天気（$day）", d.reportTime, d.stale, landscape, extra = "${dateLabel(d.date)}の予報")
        val map = TileMap(context, d, region, Weather.mapData(context)) { k -> d.tiles.map { tile(it, k) } }
        addView(map, LayoutParams(MATCH, 0, 1f).apply { setMargins(0, gap(), 0, gap()) })
        addFooter()
    }

    /** k は、タイルが多いときの縮小率（1 以下） */
    private fun tile(t: TileForecast, k: Float): View {
        val ku = u * k
        fun txt(s: String, size: Float, bold: Boolean = false, color: Int = WHITE) = TextView(context).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ku * size)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(ku * 0.15f, 0f, ku * 0.05f, 0x66000000)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (ku * 0.5f).toInt()
            setPadding(p + p / 2, p, p + p / 2, p)
        }
        box.addView(txt(t.tile.label, 1.15f, bold = true))
        val day = t.day
        val color = if (day == null) 0xFF78909C.toInt() else when (Weather.icon(day.code).first) {
            "☀️", "🌙" -> 0xFFF57C00.toInt()
            "☔" -> 0xFF1565C0.toInt()
            "⛄" -> 0xFF4FA3D9.toInt()
            else -> 0xFF78909C.toInt()
        }
        box.background = GradientDrawable().apply { setColor(color); cornerRadius = ku * 0.8f }
        if (day == null) {
            box.addView(txt("--", 1f))
            return box
        }
        val (main, sub) = Weather.icon(day.code)
        val s = SpannableStringBuilder(main)
        if (sub != null) {
            val start = s.length
            s.append(sub)
            s.setSpan(RelativeSizeSpan(0.55f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        box.addView(txt("", if (compact) 1.7f else 2.3f).apply { text = s })
        // 値が無いときは、「--」を出さずに、ある値だけを出す
        val temps = listOfNotNull(day.max?.let { "$it℃" }, day.min?.let { "$it℃" }).joinToString(" / ")
        if (temps.isNotEmpty()) box.addView(txt(temps, 0.95f))
        val pop = day.pops6h?.mapNotNull { it?.toIntOrNull() }?.maxOrNull()
        if (pop != null && k >= 0.85f) box.addView(txt("☂ $pop%", 0.8f, color = DIM))
        return box
    }

    /**
     * 地図（縦横比を保って、この地方の範囲が収まる大きさ）を描き、タイルを実際の位置に置く。
     * 重なるタイルは、ずらして、実際の位置からの引き出し線でつなぐ。タイルが多いときは、地図を隠さないよう、小さくする
     */
    private class TileMap(
        context: Context,
        private val d: RegionPageData,
        private val region: Region?,
        private val map: MapData?,
        private val makeTiles: (Float) -> List<View>,
    ) : ViewGroup(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val lead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xAAFFFFFF.toInt() }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color_WHITE }
        private var paths: List<Pair<Path, Boolean>> = emptyList()   // (都道府県, この地方か)
        private var anchors: List<FloatArray?> = emptyList()           // 各タイルの実際の位置（x, y）。pos のタイルは null
        private var built = 0   // 作った時の幅 * 10000 + 高さ

        init {
            setWillNotDraw(false)
            val dp = resources.displayMetrics.density
            stroke.strokeWidth = dp
            lead.strokeWidth = dp
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val h = MeasureSpec.getSize(heightSpec)
            setMeasuredDimension(w, h)
            if (w <= 0 || h <= 0) return
            val key = w * 10000 + h
            if (key != built) {
                built = key
                removeAllViews()
                var tiles = makeTiles(1f)
                val at = MeasureSpec.AT_MOST
                fun measureAll(list: List<View>) = list.forEach { it.measure(MeasureSpec.makeMeasureSpec(w, at), MeasureSpec.makeMeasureSpec(h, at)) }
                measureAll(tiles)
                val (mw, mh) = fitted(w, h)
                val natural = tiles.sumOf { it.measuredWidth.toDouble() * it.measuredHeight }
                val k = max(0.55f, min(1f, sqrt(0.35 * mw * mh / max(natural, 1.0)).toFloat()))
                if (k < 1f) { tiles = makeTiles(k); measureAll(tiles) }
                tiles.forEach { addView(it) }
                buildPaths(w, h)
            }
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, at()), MeasureSpec.makeMeasureSpec(h, at()))
            }
        }

        private fun at() = MeasureSpec.AT_MOST

        /** 地図の大きさ（縦横比を保ち、表示範囲が収まる大きさ） */
        private fun fitted(w: Int, h: Int): Pair<Float, Float> {
            val v = region?.view ?: return w.toFloat() to h.toFloat()
            val kx = cos(Math.toRadians(((v[2] + v[3]) / 2).toDouble())).toFloat() * (region?.stretch ?: 1f)
            val scale = min(w / ((v[1] - v[0]) * kx), h / (v[3] - v[2]))
            return (v[1] - v[0]) * kx * scale to (v[3] - v[2]) * scale
        }

        private fun buildPaths(w: Int, h: Int) {
            val v = region?.view ?: return
            val kx = cos(Math.toRadians(((v[2] + v[3]) / 2).toDouble())).toFloat() * (region?.stretch ?: 1f)
            val scale = min(w / ((v[1] - v[0]) * kx), h / (v[3] - v[2]))
            val (mw, mh) = fitted(w, h)
            val ox = (w - mw) / 2
            val oy = (h - mh) / 2
            fun px(lon: Float) = ox + (lon - v[0]) * kx * scale
            fun py(lat: Float) = oy + (v[3] - lat) * scale
            val mine = d.tiles.map { it.tile.office.take(2) }.toSet()
            paths = map?.prefs?.map { (code, rings) ->
                val p = Path()
                rings.forEach { r ->
                    for (i in 0 until r.size step 2) {
                        val x = px(r[i] / map.scale.toFloat())
                        val y = py(r[i + 1] / map.scale.toFloat())
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close()
                }
                p to (code in mine)
            } ?: emptyList()
            anchors = d.tiles.map { t ->
                if (t.tile.pos != null) null else floatArrayOf(px(t.tile.lon), py(t.tile.lat))
            }
        }

        private val centers = ArrayList<FloatArray>()

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = (r - l).toFloat()
            val h = (b - t).toFloat()
            centers.clear()
            val n = childCount
            val x = FloatArray(n); val y = FloatArray(n)
            for (i in 0 until n) {
                val tile = d.tiles.getOrNull(i)?.tile
                val a = anchors.getOrNull(i)
                x[i] = a?.get(0) ?: (w * (tile?.pos?.first ?: 50f) / 100f)
                y[i] = a?.get(1) ?: (h * (tile?.pos?.second ?: 50f) / 100f)
            }
            val gap = 6f * resources.displayMetrics.density
            repeat(80) {
                var moved = false
                for (i in 0 until n) for (j in i + 1 until n) {
                    val a = getChildAt(i); val b = getChildAt(j)
                    val dx = x[j] - x[i]; val dy = y[j] - y[i]
                    val ox = (a.measuredWidth + b.measuredWidth) / 2f + gap - abs(dx)
                    val oy = (a.measuredHeight + b.measuredHeight) / 2f + gap - abs(dy)
                    if (ox <= 0 || oy <= 0) continue
                    moved = true
                    if (ox < oy) { val s = (if (dx >= 0) 1 else -1) * ox / 2; x[i] -= s; x[j] += s }
                    else { val s = (if (dy >= 0) 1 else -1) * oy / 2; y[i] -= s; y[j] += s }
                }
                for (i in 0 until n) {
                    val c = getChildAt(i)
                    x[i] = x[i].coerceIn(c.measuredWidth / 2f, max(c.measuredWidth / 2f, w - c.measuredWidth / 2f))
                    y[i] = y[i].coerceIn(c.measuredHeight / 2f, max(c.measuredHeight / 2f, h - c.measuredHeight / 2f))
                }
                if (!moved) return@repeat
            }
            for (i in 0 until n) {
                val c = getChildAt(i)
                c.layout((x[i] - c.measuredWidth / 2).toInt(), (y[i] - c.measuredHeight / 2).toInt(),
                    (x[i] + c.measuredWidth / 2).toInt(), (y[i] + c.measuredHeight / 2).toInt())
                centers += floatArrayOf(x[i], y[i], min(c.measuredWidth, c.measuredHeight) * 0.45f)
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            // 地図 → 引き出し線 → 実際の位置の点 → タイル
            paths.forEach { (p, mine) ->
                fill.color = if (mine) 0x33FFFFFF else 0x14FFFFFF
                stroke.color = if (mine) 0x88FFFFFF.toInt() else 0x44FFFFFF
                canvas.drawPath(p, fill)
                canvas.drawPath(p, stroke)
            }
            val r = 2.5f * resources.displayMetrics.density
            anchors.forEachIndexed { i, a ->
                val c = centers.getOrNull(i) ?: return@forEachIndexed
                if (a == null) return@forEachIndexed
                if (hypot(c[0] - a[0], c[1] - a[1]) > c[2]) canvas.drawLine(a[0], a[1], c[0], c[1], lead)
                canvas.drawCircle(a[0], a[1], r, dot)
            }
            super.dispatchDraw(canvas)
        }

        private companion object {
            const val Color_WHITE = 0xFFFFFFFF.toInt()
        }
    }
}
