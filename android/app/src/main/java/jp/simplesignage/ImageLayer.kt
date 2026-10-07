package jp.simplesignage

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.min

/**
 * 画像・動画の表示方法（余白をどう扱うか）。
 * FIT: 全体を表示（余白は黒） / FIT_BLUR: 全体を表示し、余白はぼかした同じ画像で埋める /
 * FILL: 画面いっぱい（はみ出す部分は切り取り） / AUTO: 縦横比が近ければ FILL、大きく違えば FIT_BLUR
 */
object FitMode {
    const val FIT = 0
    const val FIT_BLUR = 1
    const val FILL = 2
    const val AUTO = 3

    /** おまかせで「切り取って画面いっぱい」にしてよい、切り取られる割合の上限 */
    private const val MAX_CROP = 0.2f

    /** 実際に使う表示方法（FIT / FIT_BLUR / FILL のどれか） */
    fun decide(mode: Int, contentW: Int, contentH: Int, areaW: Int, areaH: Int): Int {
        if (mode != AUTO) return mode
        if (contentW <= 0 || contentH <= 0 || areaW <= 0 || areaH <= 0) return FIT_BLUR
        val content = contentW.toFloat() / contentH
        val area = areaW.toFloat() / areaH
        val cropped = 1f - min(content / area, area / content) // 画面いっぱいにしたとき切り取られる割合
        return if (cropped <= MAX_CROP) FILL else FIT_BLUR
    }
}

/** 画像1枚分のレイヤー（ぼかした背景＋画像本体） */
class ImageLayer(val root: FrameLayout) {
    private val background = ImageView(root.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setColorFilter(0x80000000.toInt(), PorterDuff.Mode.SRC_ATOP) // 背景は暗くして本体を目立たせる
        visibility = FrameLayout.GONE
    }
    private val image = ImageView(root.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    val drawable: Drawable? get() = image.drawable

    init {
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
    }

    /** blur は小さく縮めた画像（拡大して表示するとぼけて見える） */
    fun show(d: Drawable, blur: Bitmap?, mode: Int) {
        val fit = FitMode.decide(mode, d.intrinsicWidth, d.intrinsicHeight, root.width, root.height)
        image.scaleType = if (fit == FitMode.FILL) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        image.setImageDrawable(d)
        if (fit == FitMode.FIT_BLUR && blur != null) {
            background.setImageDrawable(BitmapDrawable(root.resources, blur).apply { isFilterBitmap = true })
            background.visibility = FrameLayout.VISIBLE
        } else {
            background.setImageDrawable(null)
            background.visibility = FrameLayout.GONE
        }
    }

    fun clear() {
        image.setImageDrawable(null)
        background.setImageDrawable(null)
    }
}
