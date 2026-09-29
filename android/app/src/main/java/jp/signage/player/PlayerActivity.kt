package jp.signage.player

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * 全画面の再生画面。設定に応じて画面を区画に分け、区画ごとに
 * フォルダの画像・動画／天気予報を表示する。操作はメイン区画（最初のフォルダ区画）に対して行う。
 */
class PlayerActivity : Activity() {
    companion object {
        /** 起動直後に天気予報を表示する（設定画面のプレビュー用） */
        const val EXTRA_WEATHER_NOW = "weatherNow"
    }

    private lateinit var prefs: Prefs
    private lateinit var zonesFrame: FrameLayout
    private lateinit var infoView: TextView
    private lateinit var clockView: TextView
    private lateinit var tickerView: TickerView
    private lateinit var announcer: Announcer
    /** 読み上げ・チャイムを済ませた「止めるまで流す」テロップ（割り込み後に再開したとき鳴らさない） */
    private var announcedStandingId: String? = null
    private var zones: List<Zone> = emptyList()
    /** スワイプ・一時停止などの操作対象 */
    private var mainZone: MediaZone? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            Ticker.checkSchedules(this@PlayerActivity) // 予約したテロップの時刻か確認
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50) // 分が変わった直後に更新
        }
    }
    private val hideInfo = Runnable { infoView.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        requestedOrientation = prefs.orientation

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemUi()

        zonesFrame = findViewById(R.id.zones)
        infoView = findViewById(R.id.info)
        clockView = createClock()
        findViewById<FrameLayout>(R.id.root).addView(clockView)
        tickerView = TickerView(this).apply {
            onFinished = { showNextTicker() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> avoidTicker() }
        }
        findViewById<FrameLayout>(R.id.root).addView(
            tickerView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        )
        announcer = Announcer(this)
        infoView.bringToFront()

        zones = createZones()
        mainZone = zones.filterIsInstance<MediaZone>().firstOrNull()
        mainZone?.forceWeather = intent.getBooleanExtra(EXTRA_WEATHER_NOW, false)
        arrangeZones()
    }

    private fun createZones(): List<Zone> {
        var mainAssigned = false
        return (0 until Prefs.zoneCount(prefs.layout)).map { i ->
            when (prefs.zoneType(i)) {
                Prefs.ZONE_WEATHER -> WeatherZone(this, prefs)
                else -> {
                    val isMain = !mainAssigned
                    mainAssigned = true
                    MediaZone(
                        this, prefs, prefs.zoneFolder(i), isMain,
                        onChanged = { if (isMain) updateInfo() },
                        // 1画面のときは、天気予報の画面に時計が含まれるので重ねて表示しない
                        onPanelShown = { panel ->
                            if (isMain && prefs.clockEnabled && zones.size == 1) {
                                clockView.visibility = if (panel) View.GONE else View.VISIBLE
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * 区画を画面に並べる。メイン＋サイドは、横長の画面では右側に、縦長の画面では下側にサイドを置く。
     */
    private fun arrangeZones() {
        zones.forEach { (it.view.parent as? ViewGroup)?.removeView(it.view) }
        zonesFrame.removeAllViews()
        val gap = (resources.displayMetrics.density * 2).toInt()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        fun box(vertical: Boolean) = LinearLayout(this).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        fun LinearLayout.add(v: View, weight: Float) {
            val lp = if (orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -1, weight)
            else LinearLayout.LayoutParams(-1, 0, weight)
            lp.setMargins(gap, gap, gap, gap)
            addView(v, lp)
        }

        val v = zones.map { it.view }
        val root: View = when (prefs.layout) {
            Prefs.LAYOUT_LEFT_RIGHT, Prefs.LAYOUT_TOP_BOTTOM -> {
                val first = prefs.splitPercent.toFloat()
                box(vertical = prefs.layout == Prefs.LAYOUT_TOP_BOTTOM).apply {
                    add(v[0], first)
                    add(v[1], 100f - first)
                }
            }
            Prefs.LAYOUT_MAIN_SIDE -> box(vertical = !landscape).apply {
                add(v[0], 7f)
                add(box(vertical = landscape).apply { add(v[1], 1f); add(v[2], 1f) }, 3f)
            }
            else -> v[0]
        }
        zonesFrame.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.layout == Prefs.LAYOUT_MAIN_SIDE) arrangeZones()
    }

    /** 管理画面（別の端末）からの変更 */
    private val adminListener: (String) -> Unit = { event ->
        when (event) {
            AdminServer.EVENT_CONTENT -> zones.filterIsInstance<MediaZone>().forEach { it.reload() }
            AdminServer.EVENT_SETTINGS -> recreate() // 区画の構成なども変わるので作り直す
        }
    }

    override fun onStart() {
        super.onStart()
        clockTick.run()
        zones.forEach { it.start() }
        AdminServer.addListener(adminListener)
        AdminService.sync(this)
        Ticker.addListener(tickerListener)
        showNextTicker()
    }

    override fun onStop() {
        super.onStop()
        AdminServer.removeListener(adminListener)
        Ticker.removeListener(tickerListener)
        handler.removeCallbacks(clockTick)
        zones.forEach { it.stop() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        zones.forEach { it.release() }
        tickerView.stop()
        announcer.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- テロップ

    private val tickerListener: () -> Unit = { showNextTicker() }

    /**
     * 次のテロップを流す。回数指定のもの（呼び出しなど）を優先し、なければ「止めるまで流す」ものを流す。
     * 回数指定のものを流している間は、終わるまで次を待つ。
     */
    private fun showNextTicker() {
        if (Ticker.consumeStop()) tickerView.stop().also { avoidTicker() }
        val current = tickerView.message
        if (current != null && !current.isStanding) return
        val queued = Ticker.nextQueued()
        val next = queued ?: Ticker.standing(this)
        if (next == null) {
            tickerView.stop()
            avoidTicker()
            return
        }
        if (current != null && current.id == next.id) return // 同じものを流し中
        (tickerView.layoutParams as FrameLayout.LayoutParams).gravity =
            if (next.position == 1) Gravity.TOP else Gravity.BOTTOM
        tickerView.bringToFront()
        infoView.bringToFront()
        tickerView.show(next)
        if (queued != null || announcedStandingId != next.id) {
            announcer.announce(next)
            if (next.isStanding) announcedStandingId = next.id
        }
    }

    /** 時計がテロップと同じ側（上・下）にあるときは、帯の分だけずらして重ならないようにする */
    private fun avoidTicker() {
        val m = tickerView.message
        val h = tickerView.bandHeight.toFloat()
        val clockBottom = prefs.clockPosition == Prefs.CLOCK_BOTTOM_LEFT || prefs.clockPosition == Prefs.CLOCK_BOTTOM_RIGHT
        clockView.translationY = when {
            m == null || h == 0f -> 0f
            clockBottom && m.position == 0 -> -h
            !clockBottom && m.position == 1 -> h
            else -> 0f
        }
    }

    // ---------------------------------------------------------------- 時計

    private fun createClock() = TextView(this).apply {
        val dm = resources.displayMetrics
        val base = minOf(dm.widthPixels, dm.heightPixels)
        val size = base / when (prefs.clockSize) { 0 -> 26f; 2 -> 12f; else -> 18f }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        setTextColor(0xFFFFFFFF.toInt())
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setShadowLayer(size * 0.08f, 0f, size * 0.03f, 0x99000000.toInt())
        background = GradientDrawable().apply {
            setColor(0x66000000)
            cornerRadius = size * 0.35f
        }
        val p = (size * 0.35f).toInt()
        setPadding(p * 2, p, p * 2, p)
        val (vertical, horizontal) = when (prefs.clockPosition) {
            Prefs.CLOCK_BOTTOM_RIGHT -> Gravity.BOTTOM to Gravity.END
            Prefs.CLOCK_TOP_LEFT -> Gravity.TOP to Gravity.START
            Prefs.CLOCK_BOTTOM_LEFT -> Gravity.BOTTOM to Gravity.START
            else -> Gravity.TOP to Gravity.END
        }
        gravity = horizontal or Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(-2, -2, vertical or horizontal).apply {
            val m = (base * 0.03f).toInt()
            setMargins(m, m, m, m)
        }
        visibility = if (prefs.clockEnabled) View.VISIBLE else View.GONE
    }

    /** 日付を小さく、時刻を大きく */
    private fun updateClock() {
        if (!prefs.clockEnabled) return
        val t = LocalDateTime.now()
        val date = "${t.monthValue}/${t.dayOfMonth}(${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
        val s = SpannableStringBuilder(date).append("\n").append("${t.hour}:%02d".format(t.minute))
        s.setSpan(RelativeSizeSpan(0.45f), 0, date.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        clockView.text = s
    }

    // ---------------------------------------------------------------- 情報表示・操作

    private fun updateInfo() {
        val text = mainZone?.infoText() ?: "（フォルダの区画がありません）"
        infoView.text = "$text\nタップ: 情報　スワイプ: 前/次　ダブルタップ: 一時停止　長押し: 設定"
    }

    private fun showInfo() {
        updateInfo()
        infoView.visibility = View.VISIBLE
        infoView.removeCallbacks(hideInfo)
        infoView.postDelayed(hideInfo, 5000)
    }

    private fun toggleInfo() {
        if (infoView.visibility == View.VISIBLE) {
            infoView.removeCallbacks(hideInfo)
            infoView.visibility = View.GONE
        } else {
            showInfo()
        }
    }

    private fun next(step: Int) {
        val zone = mainZone ?: return
        if (zone.paused) zone.togglePause()
        zone.goto(step)
        showInfo()
    }

    private fun togglePause() {
        mainZone?.togglePause()
        showInfo()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_PLAYER, true)
        )
        finish()
    }

    private val gestures by lazy { GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleInfo()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            togglePause()
            return true
        }

        override fun onLongPress(e: MotionEvent) = openSettings()

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            if (abs(dx) < 100 || abs(dx) < abs(e2.y - start.y)) return false
            next(if (dx < 0) 1 else -1)
            return true
        }
    }) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> next(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> next(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePause()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> toggleInfo()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> openSettings()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
