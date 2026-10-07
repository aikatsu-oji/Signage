package jp.simplesignage

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * テレビのリモコン（十字キー）で操作しやすくするための補助。
 * - 選択中の項目を枠で囲んで分かりやすくする（タッチ操作中は出さない）
 * - 横長の大画面では、設定項目が横に広がりすぎないよう中央に寄せる
 */
object RemoteFocus {
    fun install(activity: Activity, scroll: ScrollView) {
        val density = activity.resources.displayMetrics.density
        val highlight = GradientDrawable().apply {
            setColor(0x331E88E5)
            setStroke((3 * density).toInt(), activity.getColor(R.color.brand))
            cornerRadius = 8 * density
        }
        var current: View? = null
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            current?.foreground = null
            current = null
            if (newFocus != null && !newFocus.isInTouchMode && newFocus !is ViewGroup) {
                newFocus.foreground = highlight
                current = newFocus
            }
        }

        // 横長の大画面（テレビ）では幅を抑えて中央に
        val content = scroll.getChildAt(0) ?: return
        scroll.post {
            val maxWidth = (760 * density).toInt()
            val extra = scroll.width - maxWidth
            if (extra > 0) {
                content.setPadding(extra / 2, content.paddingTop, extra / 2, content.paddingBottom)
            }
        }
    }

    /** 画面の先頭から表示し、最初の操作項目にフォーカスを置く */
    fun focusFirst(scroll: ScrollView, first: View) {
        scroll.post {
            scroll.scrollTo(0, 0)
            first.requestFocus()
        }
    }
}
