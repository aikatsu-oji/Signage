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

/** 天気予報の各画面に表示するデータ一式 */
class WeatherPages(
    val daily: WeatherData?,
    val series: TimeSeriesData?,
    val cityName: String?,
    val areaName: String?,
) {
    val isEmpty get() = daily == null && series == null

    companion object {
        /** 設定の地域の予報を読み込む（通信するのでバックグラウンドで呼ぶ） */
        fun load(context: Context, prefs: Prefs): WeatherPages {
            val office = prefs.weatherOffice ?: return WeatherPages(null, null, null, null)
            val area = prefs.weatherArea
            val daily = Weather.get(context, office, area)
            val series = if (prefs.weatherTimeSeries && area != null) {
                Weather.getTimeSeries(context, area)?.takeIf { it.slots.isNotEmpty() }
            } else {
                null
            }
            return WeatherPages(daily, series, prefs.weatherCityName, daily?.areaName ?: prefs.weatherAreaName)
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
    private var pages: List<View> = emptyList()
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
        if (prefs.weatherOffice == null) {
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
                val list = mutableListOf<View>()
                data.daily?.let { weatherView.bind(it, data.cityName); list += weatherView }
                data.series?.let { timeSeriesView.bind(it, data.cityName, data.areaName); list += timeSeriesView }
                pages = list
                page = 0
                show(my)
            }
        }
    }

    private fun show(my: Int) {
        val target = pages[page]
        pages.filter { it !== target }.forEach { it.animate().alpha(0f).setDuration(prefs.fadeMillis).start() }
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
