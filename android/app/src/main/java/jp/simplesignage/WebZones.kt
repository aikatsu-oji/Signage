package jp.simplesignage

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

object ZoneUrl {
    /** http / https の URL だけ受け付ける。正しければ空、だめなら理由 */
    fun check(url: String): String {
        val u = runCatching { java.net.URI(url.trim()) }.getOrNull()
        if (u == null || (u.scheme != "http" && u.scheme != "https") || u.host.isNullOrEmpty()) {
            return "http:// か https:// で始まる URL を入力してください"
        }
        if (url.length > 500) return "URL が長すぎます"
        return ""
    }
}

/** 指定した URL の Web ページを表示し、設定した間隔で読み直す区画 */
class WebZone(private val activity: Activity, private val prefs: Prefs, private val zoneIndex: Int) : Zone {
    override val view = FrameLayout(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val message = TextView(activity).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFF666666.toInt())
        textSize = 16f
        visibility = View.GONE
    }
    private var web: WebView? = null
    private var reload: Runnable? = null

    init {
        view.setBackgroundColor(0xFFFFFFFF.toInt())
        view.addView(message, FrameLayout.LayoutParams(-1, -1))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun start() {
        val url = prefs.zoneUrl(zoneIndex)
        if (url.isEmpty() || ZoneUrl.check(url).isNotEmpty()) {
            message.text = "Web ページの URL が設定されていません\n管理画面の「画面分割」で設定してください"
            message.visibility = View.VISIBLE
            return
        }
        message.visibility = View.GONE
        val w = web ?: WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) {
                        message.text = "ページを表示できません\n1分後に再試行します"
                        message.visibility = View.VISIBLE
                        handler.postDelayed({ if (web === v) { message.visibility = View.GONE; v.loadUrl(prefs.zoneUrl(zoneIndex)) } }, 60_000)
                    }
                }
            }
            view.addView(this, 0, FrameLayout.LayoutParams(-1, -1))
            web = this
        }
        w.onResume()
        w.loadUrl(url)
        val min = prefs.zoneRefreshMin(zoneIndex)
        reload?.let { handler.removeCallbacks(it) }
        reload = object : Runnable {
            override fun run() {
                web?.loadUrl(prefs.zoneUrl(zoneIndex))
                handler.postDelayed(this, min * 60_000L)
            }
        }.also { handler.postDelayed(it, min * 60_000L) }
    }

    override fun stop() {
        reload?.let { handler.removeCallbacks(it) }
        web?.onPause()
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        web?.let { view.removeView(it); it.destroy() }
        web = null
    }
}

/** RSS / Atom の見出しを、数件ずつ切り替えて表示する区画 */
class RssZone(private val activity: Activity, private val prefs: Prefs, private val zoneIndex: Int) : Zone {
    override val view = FrameLayout(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val head = TextView(activity).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 14f }
    private val list = TextView(activity).apply { setTextColor(0xFFFFFFFF.toInt()); setLineSpacing(0f, 1.3f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val message = TextView(activity).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFFAAAAAA.toInt())
        textSize = 16f
        visibility = View.GONE
    }
    private var token = 0
    private var items: List<String> = emptyList()
    private var page = 0
    private var refreshAt = 0L

    init {
        view.setBackgroundColor(0xFF10151F.toInt())
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (activity.resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad, pad, pad)
            addView(head)
            addView(list)
        }
        view.addView(box, FrameLayout.LayoutParams(-1, -1))
        view.addView(message, FrameLayout.LayoutParams(-1, -1))
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 大きさに合わせて文字の大きさを決める
            val s = minOf(view.width, view.height).coerceAtLeast(1)
            list.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s * 0.06f)
            head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s * 0.035f)
        }
    }

    override fun start() {
        token++
        load(token)
    }

    private fun load(my: Int) {
        handler.removeCallbacksAndMessages(null)
        io.execute {
            val result = runCatching { fetch(prefs.zoneUrl(zoneIndex)) }
            handler.post {
                if (my != token) return@post
                val got = result.getOrNull()
                if (got.isNullOrEmpty()) {
                    message.text = "ニュースを取得できません\n" + (if (prefs.zoneUrl(zoneIndex).isEmpty()) "RSS の URL が設定されていません" else "1分後に再試行します")
                    message.visibility = View.VISIBLE
                    handler.postDelayed({ if (my == token) load(my) }, 60_000)
                    return@post
                }
                message.visibility = View.GONE
                items = got
                page = 0
                refreshAt = System.currentTimeMillis() + prefs.zoneRefreshMin(zoneIndex) * 60_000L
                show(my)
            }
        }
    }

    private fun show(my: Int) {
        if (my != token) return
        val per = if (view.height > view.width) 5 else 4
        val start = page * per
        val chunk = items.drop(start).take(per)
        head.text = "ニュース（${minOf(start + per, items.size)} / ${items.size}）"
        list.text = chunk.joinToString("\n") { "・$it" }
        page = if (start + per < items.size) page + 1 else 0
        val wrapped = page == 0
        handler.postDelayed({
            if (my != token) return@postDelayed
            if (wrapped && System.currentTimeMillis() >= refreshAt) load(my) else show(my)
        }, 10_000)
    }

    /** 見出しを取り出す（RSS 2.0 / RDF の item、Atom の entry の title） */
    private fun fetch(url: String): List<String> {
        require(ZoneUrl.check(url).isEmpty())
        // 転送（リダイレクト）は自分で追い、行き先ごとに、端末自身・リンクローカルでないかを確かめる
        var target = url
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            requirePublicHost(target)
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SignagePlayer/1.0")
            }
            val code = conn.responseCode
            if (code !in 300..399) break
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            if (next == null || ++hops > 5) throw java.io.IOException("too many redirects")
            target = URL(URL(target), next).toString()
            require(ZoneUrl.check(target).isEmpty())
        }
        try {
            val bytes = conn.inputStream.use { it.readNBytesLimited(1_000_000) }
            val f = DocumentBuilderFactory.newInstance().apply {
                // 外部の定義を読み込まない（XXE 対策）
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isNamespaceAware = false
            }
            val doc = f.newDocumentBuilder().parse(bytes.inputStream())
            val out = ArrayList<String>()
            for (tag in listOf("item", "entry")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as Element
                    val t = e.getElementsByTagName("title")
                    if (t.length > 0) t.item(0).textContent.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
            return out.take(30)
        } finally {
            conn.disconnect()
        }
    }

    /** この端末自身（127.x・::1）、リンクローカル（169.254.x.x など）、未指定アドレスへは取りに行かない */
    private fun requirePublicHost(url: String) {
        val host = URL(url).host
        for (a in java.net.InetAddress.getAllByName(host)) {
            require(!(a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress)) { "この端末自身の URL は指定できません" }
        }
    }

    private fun java.io.InputStream.readNBytesLimited(limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
            if (buf.size() > limit) throw java.io.IOException("too large")
        }
        return buf.toByteArray()
    }

    override fun stop() {
        token++
        handler.removeCallbacksAndMessages(null)
    }

    override fun release() {
        token++
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }
}
