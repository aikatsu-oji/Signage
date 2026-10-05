package jp.signage.player

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** 天気予報の 1 画面分（日ごと、または 3 時間ごと）。表示する直前に、画面の部品へ割り当てる */
sealed class WeatherPage {
    class Daily(val data: WeatherData, val cityName: String?) : WeatherPage()
    class Series(val data: TimeSeriesData, val cityName: String?, val areaName: String?) : WeatherPage()
}

/** 天気予報の各画面に表示するデータ一式（設定の地域 + 追加した地域の順） */
class WeatherPages(val pages: List<WeatherPage>) {
    val isEmpty get() = pages.isEmpty()

    companion object {
        /** 設定の地域（と追加した地域）の予報を読み込む（通信するのでバックグラウンドで呼ぶ） */
        fun load(context: Context, prefs: Prefs): WeatherPages {
            val places = mutableListOf<WeatherPlace>()
            prefs.weatherOffice?.let { places += WeatherPlace(it, prefs.weatherArea, prefs.weatherAreaName, prefs.weatherCityName) }
            places += prefs.weatherExtra
            val pages = mutableListOf<WeatherPage>()
            for (p in places) {
                val daily = runCatching { Weather.get(context, p.office, p.area) }.getOrNull()
                val series = if (prefs.weatherTimeSeries && p.area != null) {
                    runCatching { Weather.getTimeSeries(context, p.area) }.getOrNull()?.takeIf { it.slots.isNotEmpty() }
                } else {
                    null
                }
                val area = daily?.areaName ?: p.areaName
                daily?.let { pages += WeatherPage.Daily(it, p.cityName) }
                series?.let { pages += WeatherPage.Series(it, p.cityName, area) }
            }
            return WeatherPages(pages)
        }
    }
}

/** 天気予報を常に表示する区画（日ごと → 3時間ごとを交互に表示） */
class WeatherZone(private val activity: Activity, private val prefs: Prefs) : Zone {
    override val view = FrameLayout(activity)
    private val weatherView = WeatherView(activity)
    private val timeSeriesView = TimeSeriesView(activity).apply { visibility = View.INVISIBLE }
    private val message = TextView(activity).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFFAAAAAA.toInt())
        textSize = 16f
        visibility = View.GONE
    }
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var token = 0
    private var pages: List<WeatherPage> = emptyList()
    private var page = 0

    init {
        view.setBackgroundColor(0xFF0D2A5C.toInt())
        view.addView(weatherView, FrameLayout.LayoutParams(-1, -1))
        view.addView(timeSeriesView, FrameLayout.LayoutParams(-1, -1))
        view.addView(message, FrameLayout.LayoutParams(-1, -1))
    }

    override fun start() {
        page = 0
        reload(++token)
    }

    override fun stop() {
        token++
        handler.removeCallbacksAndMessages(null)
    }

    override fun release() {
        stop()
        io.shutdownNow()
    }

    /** 予報を読み込み直して先頭の画面から表示（データは30分キャッシュされる） */
    private fun reload(my: Int) {
        if (prefs.weatherOffice == null && prefs.weatherExtra.isEmpty()) {
            showMessage("天気予報の地域が設定されていません")
            return
        }
        io.execute {
            val data = WeatherPages.load(activity, prefs)
            handler.post {
                if (my != token) return@post
                if (data.isEmpty) {
                    showMessage("天気予報を取得できません\nインターネット接続を確認してください\n（1分後に再試行）")
                    handler.postDelayed({ if (my == token) reload(my) }, 60_000)
                    return@post
                }
                showMessage(null)
                pages = data.pages
                page = 0
                show(my)
            }
        }
    }

    private fun show(my: Int) {
        val target: View = when (val p = pages[page]) {
            is WeatherPage.Daily -> weatherView.also { it.bind(p.data, p.cityName) }
            is WeatherPage.Series -> timeSeriesView.also { it.bind(p.data, p.cityName, p.areaName) }
        }
        listOf<View>(weatherView, timeSeriesView).filter { it !== target }.forEach { it.animate().alpha(0f).setDuration(prefs.fadeMillis).start() }
        target.visibility = View.VISIBLE
        target.bringToFront()
        target.animate().alpha(1f).setDuration(prefs.fadeMillis).start()
        handler.postDelayed({
            if (my != token) return@postDelayed
            page++
            if (page >= pages.size) reload(my) else show(my)
        }, prefs.weatherSeconds * 1000L)
    }

    private fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) message.bringToFront()
    }
}
