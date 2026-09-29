package jp.signage.player

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** 画面の1区画 */
interface Zone {
    val view: View
    fun start()
    fun stop()
    fun release()
}

/**
 * フォルダ内の画像・動画をループ再生する区画。
 * メイン区画 (isMain) だけが動画の音声を出し、一定間隔の天気予報を差し込む。
 */
class MediaZone(
    private val activity: Activity,
    private val prefs: Prefs,
    private val folder: Uri?,
    private val isMain: Boolean,
    /** 表示内容が変わったとき（情報表示の更新用） */
    private val onChanged: () -> Unit = {},
    /** 天気予報の画面を表示中かどうか（時計の重ね表示を切り替えるため） */
    private val onPanelShown: (Boolean) -> Unit = {},
) : Zone {
    override val view: FrameLayout =
        LayoutInflater.from(activity).inflate(R.layout.zone_media, null) as FrameLayout
    private val playerView: PlayerView = view.findViewById(R.id.playerView)
    private val imageA: ImageView = view.findViewById(R.id.imageA)
    private val imageB: ImageView = view.findViewById(R.id.imageB)
    private val messageView: TextView = view.findViewById(R.id.message)
    private val weatherView = WeatherView(activity).apply { visibility = View.INVISIBLE }
    private val timeSeriesView = TimeSeriesView(activity).apply { visibility = View.INVISIBLE }
    private val layers: List<View> = listOf(playerView, imageA, imageB, weatherView, timeSeriesView)
    private val player = ExoPlayer.Builder(activity).build()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var playlist: List<MediaEntry> = emptyList()
    private var index = -1
    /** 古い非同期処理を無効化するための世代番号 */
    private var token = 0
    private var revealGeneration = 0
    private var showing: View? = null
    private var currentIsVideo = false
    var paused = false
        private set
    private var imageStartedAt = 0L
    private var imageRemaining = 0L
    private var watchdog: Runnable? = null
    private var nextImage: Runnable? = null
    private var showingWeather = false
    private var lastWeatherAt = System.currentTimeMillis()
    /** 次の切り替わりで天気予報を表示する（プレビュー用） */
    var forceWeather = false
    /** 天気予報の、これから表示する画面（日ごと → 3時間ごと） */
    private val weatherPages = ArrayDeque<View>()

    init {
        view.addView(weatherView, 1, FrameLayout.LayoutParams(-1, -1))
        view.addView(timeSeriesView, 1, FrameLayout.LayoutParams(-1, -1))
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!currentIsVideo) return
                cancelWatchdog()
                reveal(playerView)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && currentIsVideo) goto(1)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (currentIsVideo) handler.post { goto(1) }
            }
        })
    }

    override fun start() {
        // 前面に戻ったら、表示中だった項目から再開
        if (index >= 0 && !showingWeather) index--
        showingWeather = false
        weatherPages.clear()
        paused = false
        goto(1)
    }

    override fun stop() {
        token++
        cancelTimers()
        player.pause()
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        player.release()
    }

    // ---------------------------------------------------------------- 再生制御

    /**
     * step 分だけ前後に移動して再生する。
     * リストの端を越えたときはフォルダを再スキャンして、追加・削除されたファイルを反映する。
     */
    fun goto(step: Int) {
        val my = ++token
        cancelTimers()
        // 天気予報の続きの画面があれば表示
        if (step > 0 && showingWeather && weatherPages.isNotEmpty()) {
            showWeatherPage(weatherPages.removeFirst(), my)
            return
        }
        weatherPages.clear()
        // 一定間隔ごとに、次の項目へ進む代わりに天気予報を差し込む
        if (step > 0 && !showingWeather && isWeatherDue()) {
            showWeather(my)
            return
        }
        showingWeather = false
        val next = index + step
        if (next in playlist.indices) {
            index = next
            play(playlist[index], my)
            return
        }

        val folder = folder
        if (folder == null) {
            showMessage("この区画のフォルダが設定されていません\n長押しで設定画面を開きます")
            return
        }
        val wrapBack = next < 0
        val recursive = prefs.recursive
        io.execute {
            val result = runCatching { MediaScanner.scan(activity.contentResolver, folder, recursive) }
            handler.post {
                if (my != token || activity.isFinishing) return@post
                val items = result.getOrNull()
                if (items == null) {
                    showMessage("フォルダを読み込めません。\nUSBメモリ等が外れていないか確認してください。\n\n5秒後に再試行します（長押しで設定）")
                    handler.postDelayed({ if (my == token) goto(step) }, 5000)
                    return@post
                }
                playlist = if (prefs.shuffle) items.shuffled() else items
                if (playlist.isEmpty()) {
                    index = -1
                    currentIsVideo = false
                    layers.forEach(::hideLayer)
                    showing = null
                    showMessage("再生できる画像・動画がありません\n${MediaScanner.describe(folder)}\n\n5秒ごとに再確認します（長押しで設定）")
                    handler.postDelayed({ if (my == token) goto(1) }, 5000)
                    return@post
                }
                index = if (wrapBack) playlist.lastIndex else 0
                play(playlist[index], my)
            }
        }
    }

    private fun play(item: MediaEntry, my: Int) {
        showMessage(null)
        currentIsVideo = item.isVideo
        onChanged()
        // 読み込みが終わらないファイルで止まらないように保険
        watchdog = Runnable { if (my == token) goto(1) }.also { handler.postDelayed(it, 20_000) }

        if (item.isVideo) {
            player.volume = if (isMain && prefs.videoSound) 1f else 0f
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.prepare()
            player.playWhenReady = !paused
            // 表示切り替えは onRenderedFirstFrame で行う
        } else {
            player.pause() // 動画から画像へ移る場合、音声が残らないように止める
            val target = if (showing === imageA) imageB else imageA
            val maxSide = max(view.width, view.height).takeIf { it > 0 }
                ?: activity.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }
            io.execute {
                val drawable = loadImage(item.uri, maxSide)
                handler.post {
                    if (my != token) return@post
                    cancelWatchdog()
                    if (drawable == null) {
                        goto(1)
                        return@post
                    }
                    target.setImageDrawable(drawable)
                    startAnimation(drawable)
                    reveal(target)
                    startImageTimer(prefs.imageSeconds * 1000L, my)
                }
            }
        }
    }

    private fun isWeatherDue(): Boolean {
        if (!isMain || prefs.weatherOffice == null) return false
        if (forceWeather) return true
        return prefs.weatherEnabled &&
            System.currentTimeMillis() - lastWeatherAt >= prefs.weatherIntervalMin * 60_000L
    }

    private fun showWeather(my: Int) {
        forceWeather = false
        lastWeatherAt = System.currentTimeMillis()
        showingWeather = true
        currentIsVideo = false
        player.pause()
        showMessage(null)
        watchdog = Runnable { if (my == token) goto(1) }.also { handler.postDelayed(it, 20_000) }
        io.execute {
            val pages = WeatherPages.load(activity, prefs)
            handler.post {
                if (my != token) return@post
                cancelWatchdog()
                weatherPages.clear()
                pages.daily?.let { weatherView.bind(it, pages.cityName); weatherPages += weatherView }
                pages.series?.let { timeSeriesView.bind(it, pages.cityName, pages.areaName); weatherPages += timeSeriesView }
                if (weatherPages.isEmpty()) {
                    // 一度も取得できていない（未接続など）ときは飛ばして再生を続ける
                    goto(1)
                    return@post
                }
                showWeatherPage(weatherPages.removeFirst(), my)
            }
        }
    }

    private fun showWeatherPage(page: View, my: Int) {
        reveal(page)
        onChanged()
        startImageTimer(prefs.weatherSeconds * 1000L, my)
    }

    private fun startImageTimer(ms: Long, my: Int) {
        imageStartedAt = System.currentTimeMillis()
        imageRemaining = ms
        if (paused) return
        nextImage = Runnable { if (my == token) goto(1) }.also { handler.postDelayed(it, ms) }
    }

    private fun cancelWatchdog() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
    }

    private fun cancelTimers() {
        cancelWatchdog()
        nextImage?.let(handler::removeCallbacks)
        nextImage = null
    }

    fun togglePause() {
        paused = !paused
        if (currentIsVideo) {
            player.playWhenReady = !paused
        } else if (paused) {
            nextImage?.let(handler::removeCallbacks)
            imageRemaining -= System.currentTimeMillis() - imageStartedAt
        } else if (index >= 0 || showingWeather) {
            startImageTimer(max(imageRemaining, 0), token)
        }
        onChanged()
    }

    /** 情報表示用の説明文 */
    fun infoText(): String? {
        val pause = if (paused) "　⏸ 一時停止中" else ""
        if (showingWeather) return "天気予報$pause"
        val item = playlist.getOrNull(index) ?: return null
        return "${index + 1} / ${playlist.size}　[${if (item.isVideo) "動画" else "画像"}] ${item.name}$pause"
    }

    // ---------------------------------------------------------------- 表示

    /**
     * 新しいレイヤーを表示し、終わったら古いレイヤーを片付ける。
     * 動画 (TextureView) は非表示にすると描画面が作られないため、常に最背面で表示したままにし、
     * 画像はその上に重ねてフェードイン／フェードアウトさせる。
     */
    private fun reveal(target: View) {
        if (showing === target) {
            target.alpha = 1f
            target.visibility = View.VISIBLE
            return
        }
        val old = showing
        val gen = ++revealGeneration
        layers.forEach { it.animate().cancel() }
        layers.filter { it !== target && it !== old }.forEach(::hideLayer)
        showing = target
        onPanelShown(target is PanelView)

        if (target === playerView) {
            // 上に載っている画像をフェードアウトして、背面の動画を見せる
            old?.animate()
                ?.alpha(0f)
                ?.setDuration(prefs.fadeMillis)
                ?.withEndAction { if (gen == revealGeneration) hideLayer(old) }
                ?.start()
        } else {
            target.alpha = 0f
            target.visibility = View.VISIBLE
            target.bringToFront()
            messageView.bringToFront()
            target.animate()
                .alpha(1f)
                .setDuration(prefs.fadeMillis)
                .withEndAction { if (gen == revealGeneration && old != null) hideLayer(old) }
                .start()
        }
    }

    private fun hideLayer(v: View) {
        if (v === playerView) {
            // 動画レイヤーは表示したまま、再生だけ止める
            if (!currentIsVideo) {
                player.stop()
                player.clearMediaItems()
            }
            return
        }
        v.visibility = View.INVISIBLE
        v.alpha = 0f
        if (v is ImageView) {
            stopAnimation(v.drawable)
            v.setImageDrawable(null)
        }
    }

    /** GIF / アニメーション WebP を繰り返し再生（Android 9 以降） */
    private fun startAnimation(d: Drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
        }
    }

    private fun stopAnimation(d: Drawable?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.stop()
    }

    /** 区画の大きさに合わせて縮小しながら読み込む（大きな写真でのメモリ不足対策） */
    private fun loadImage(uri: Uri, maxSide: Int): Drawable? = try {
        val resolver = activity.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val ratio = min(maxSide.toFloat() / w, maxSide.toFloat() / h)
                if (ratio < 1f) decoder.setTargetSize(max(1, (w * ratio).toInt()), max(1, (h * ratio).toInt()))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.let { BitmapDrawable(activity.resources, it) }
        }
    } catch (t: Throwable) {
        null
    }

    private fun showMessage(text: String?) {
        messageView.text = text
        messageView.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) messageView.bringToFront()
    }
}
