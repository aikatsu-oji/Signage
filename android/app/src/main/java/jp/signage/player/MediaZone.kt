package jp.signage.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.AspectRatioFrameLayout
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
 * （デコーダーの切り替え設定に Media3 の UnstableApi を使う）
 * メイン区画 (isMain) だけが動画の音声を出し、一定間隔の天気予報を差し込む。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MediaZone(
    private val activity: Activity,
    private val prefs: Prefs,
    private val folder: Uri?,
    private val isMain: Boolean,
    /** 表示内容が変わったとき（情報表示の更新用） */
    private val onChanged: () -> Unit = {},
    /** 天気予報の画面を表示中かどうか（時計の重ね表示を切り替えるため） */
    private val onPanelShown: (Boolean) -> Unit = {},
    /** 何番目の区画か（ファイルごとの再生条件の参照用） */
    private val zoneIndex: Int = 0,
) : Zone {
    override val view: FrameLayout =
        // 動画の互換モードでは、専用ボードでも安定しやすい SurfaceView で描画する
        LayoutInflater.from(activity).inflate(
            // 縦向きで画面を回して表示するときは、回転が効く TextureView を使う
            if (prefs.videoCompat && !PlayerActivity.needsSoftRotation(activity, prefs)) R.layout.zone_media_surface else R.layout.zone_media, null
        ) as FrameLayout
    private val playerView: PlayerView = view.findViewById(R.id.playerView)
    private val imageA = ImageLayer(view.findViewById(R.id.imageA))
    private val imageB = ImageLayer(view.findViewById(R.id.imageB))
    private val messageView: TextView = view.findViewById(R.id.message)
    private val weatherView = WeatherView(activity).apply { visibility = View.INVISIBLE }
    private val timeSeriesView = TimeSeriesView(activity).apply { visibility = View.INVISIBLE }
    private val layers: List<View> = listOf(playerView, imageA.root, imageB.root, weatherView, timeSeriesView)
    // 端末の動画デコーダーが使えないときは、別のデコーダーに切り替えて再生する
    /**
     * 複数の区画で動画を同時に再生すると、端末のハードウェアデコーダーが足りなくなって止まることがある。
     * 2 つ目以降の動画の区画（メインでない区画）は、ソフトウェアデコーダーを先に使う
     */
    private val softFirst = !isMain && prefs.videoMultiSoft

    private val player = ExoPlayer.Builder(
        activity,
        DefaultRenderersFactory(activity).setEnableDecoderFallback(true).apply {
            if (softFirst) {
                setMediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
                }
            }
        },
    ).build()

    /** 音声のトラックを無効にしているか（音を出さない区画は、音声のデコーダーも使わない） */
    private var audioDisabled = false

    /** 動画が固まったことを見つけるための記録 */
    private var lastPosition = -1L
    private var lastProgressAt = 0L
    private var stallCheck: Runnable? = null

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
        // 区画の大きさが変わったら（向きが変わったときなど）、回転の大きさを合わせ直す
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (rotation != 0 && (r - l != or2 - ol || b - t != ob - ot)) applyRotation(rotation)
        }
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!currentIsVideo) return
                cancelWatchdog()
                reveal(playerView)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && currentIsVideo) goto(1)
            }

            /** 動画の縦横比が分かったら、表示方法（全体を表示／画面いっぱい）を決める */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                val fit = FitMode.decide(prefs.fitMode, w, videoSize.height, playerView.width, playerView.height)
                playerView.resizeMode = if (fit == FitMode.FILL) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying || !currentIsVideo) return
                cancelWatchdog()
                // 機種によっては「最初の1コマ」の通知が来ないので、再生が始まったら少し待って表示する
                val my = token
                handler.postDelayed({
                    if (my == token && currentIsVideo && showing !== playerView) reveal(playerView)
                }, 1500)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (currentIsVideo) PlayerStatus.issue("再生エラー ${error.errorCodeName}：$currentName ${videoInfo()}")
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

    /** フォルダを読み直して先頭から再生し直す（管理画面でファイルが変わったとき） */
    fun reload() {
        playlist = emptyList()
        index = -1
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
        // 再生条件（時間帯・曜日・期間）に合わないファイルは飛ばす
        val next = nextActive(index + step, if (step < 0) -1 else 1)
        if (next != null) {
            index = next
            play(playlist[index], my)
            return
        }
        val wrapBack = index + step < 0

        val folder = folder
        if (folder == null) {
            showMessage("この区画のフォルダが設定されていません\n長押しで設定画面を開きます")
            return
        }
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
                val first = nextActive(if (wrapBack) playlist.lastIndex else 0, if (wrapBack) -1 else 1)
                if (first == null) {
                    // 全ファイルが条件外の時間帯。時間が来るまで待つ
                    index = -1
                    currentIsVideo = false
                    layers.forEach(::hideLayer)
                    showing = null
                    showMessage("いま再生する条件に合うファイルがありません\n（時間帯・曜日・期間の設定を確認してください）")
                    handler.postDelayed({ if (my == token) goto(1) }, 5000)
                    return@post
                }
                index = first
                play(playlist[index], my)
            }
        }
    }

    /** start から dir 方向へ、いま再生してよい最初のファイルの位置。範囲内に無ければ null */
    private fun nextActive(start: Int, dir: Int): Int? {
        val now = java.util.Calendar.getInstance()
        var i = start
        while (i in playlist.indices) {
            if (FileRule.isActive(prefs.fileRule(zoneIndex, playlist[i].name), now)) return i
            i += dir
        }
        return null
    }

    /** 声の放送中は、動画の音を小さくする */
    private var ducked = false

    fun duck(on: Boolean) {
        ducked = on
        applyVolume()
    }

    private fun applyVolume() {
        val base = if (isMain && prefs.videoSound) 1f else 0f
        player.volume = if (ducked) base * 0.15f else base
        // 音を出さない区画は、音声のデコーダーを使わない（動画を複数同時に再生するときの負担を減らす）
        val noAudio = base == 0f
        if (noAudio != audioDisabled) {
            audioDisabled = noAudio
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, noAudio).build()
        }
    }

    /** いま再生している画像・動画の回転（0・90・180・270） */
    private var rotation = 0

    /** いま再生している項目のファイル名（問題の記録用） */
    private var currentName = ""

    /**
     * 画像・動画のレイヤーを回転する。90・270 度は、区画の縦横を入れ替えた大きさにして中央で回す
     * （回したあとの見た目が区画にちょうど収まり、表示方法の判定も入れ替えた大きさで行われる）。
     * ※ 動画の互換モード（SurfaceView）では、画面の回転は効かない
     */
    private fun applyRotation(deg: Int) {
        rotation = deg
        val zw = view.width
        val zh = view.height
        for (l in listOf<View>(playerView, imageA.root, imageB.root)) {
            val lp = l.layoutParams as FrameLayout.LayoutParams
            val side = (deg == 90 || deg == 270) && zw > 0 && zh > 0
            lp.width = if (side) zh else FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = if (side) zw else FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = if (side) Gravity.CENTER else Gravity.NO_GRAVITY
            l.layoutParams = lp
            l.rotation = if (zw > 0 && zh > 0) deg.toFloat() else 0f
        }
    }

    private fun play(item: MediaEntry, my: Int) {
        applyRotation(prefs.fileRotation(zoneIndex, item.name))
        currentName = item.name
        showMessage(null)
        currentIsVideo = item.isVideo
        PlayerStatus.media(zoneIndex, item.name, item.isVideo, index + 1, playlist.size)
        onChanged()
        // 読み込みが終わらないファイルで止まらないように保険
        watchdog = Runnable { if (my == token) goto(1) }.also { handler.postDelayed(it, 20_000) }

        if (item.isVideo) {
            applyVolume()
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.prepare()
            player.playWhenReady = !paused
            // 表示切り替えは onRenderedFirstFrame（来ない機種は onIsPlayingChanged）で行う
            startStallCheck(my)
        } else {
            player.pause() // 動画から画像へ移る場合、音声が残らないように止める
            val target = if (showing === imageA.root) imageB else imageA
            val mode = prefs.fitMode
            val maxSide = max(view.width, view.height).takeIf { it > 0 }
                ?: activity.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }
            io.execute {
                val drawable = loadImage(item.uri, maxSide)
                // 余白をぼかして埋める場合に使う、小さく縮めた画像
                val blur = if (drawable != null && (mode == FitMode.FIT_BLUR || mode == FitMode.AUTO)) {
                    loadBlur(item.uri)
                } else {
                    null
                }
                handler.post {
                    if (my != token) return@post
                    cancelWatchdog()
                    if (drawable == null) {
                        goto(1)
                        return@post
                    }
                    target.show(drawable, blur, mode)
                    startAnimation(drawable)
                    reveal(target.root)
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
        PlayerStatus.media(zoneIndex, "天気予報", false, 0, 0)
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

    /**
     * 再生位置が一定時間進まなければ「固まった」とみなし、まずその位置から読み込み直す。
     * それでも進まなければ次の項目へ進む（専用ボードなどで映像が止まったままになるのを防ぐ）。
     */
    /** 動画の形式（止まる動画の特徴を調べるための記録） */
    private fun videoInfo(): String {
        val f = player.videoFormat ?: return ""
        val fps = if (f.frameRate > 0) "${f.frameRate.toInt()}fps" else ""
        val kbps = if (f.bitrate > 0) "${f.bitrate / 1000}kbps" else ""
        return "（${f.sampleMimeType ?: "?"} ${f.width}x${f.height} $fps $kbps）".replace("  ", " ")
    }

    private fun stateName() = when (player.playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?"
    }

    /**
     * 動画が途中で固まったとき、段階的に復旧する：6 秒止まったら少し先へ移動 → さらに 6 秒で読み込み直し → さらに 6 秒で次へ進む。
     * （再生を始めた直後は、読み込みに時間がかかることがあるので 15 秒まで待つ）。止まった記録は、管理画面の配信状況に出る
     */
    private fun startStallCheck(my: Int) {
        lastPosition = -1L
        lastProgressAt = System.currentTimeMillis()
        var step = 0
        var stallPos = -1L // 最後に止まった位置（自分で動かした分を、復旧とは数えないため）
        var everPlayed = false
        stallCheck = object : Runnable {
            override fun run() {
                if (my != token || !currentIsVideo) return
                val now = System.currentTimeMillis()
                val position = player.currentPosition
                val progressed = position != lastPosition
                if (paused || !player.playWhenReady || progressed) {
                    if (progressed && position > 0) {
                        everPlayed = true
                        // 止まった位置より 3 秒以上先まで再生できたら、復旧できたとみなす
                        if (stallPos < 0 || position > stallPos + 3000) {
                            step = 0
                            stallPos = -1L
                        }
                    }
                    lastPosition = position
                    lastProgressAt = now
                } else if (now - lastProgressAt > (if (everPlayed) 6_000 else 15_000)) {
                    step++
                    stallPos = position
                    lastProgressAt = now
                    PlayerStatus.issue("止まりました（復旧 $step 段階目）：$currentName ${videoInfo()} 位置 ${position / 1000}秒 状態 ${stateName()}")
                    when (step) {
                        1 -> {
                            player.seekTo(position + 500)
                            player.playWhenReady = true
                        }
                        2 -> {
                            player.prepare()
                            player.seekTo(position + 500)
                            player.playWhenReady = true
                        }
                        else -> {
                            goto(1)
                            return
                        }
                    }
                }
                handler.postDelayed(this, 2000)
            }
        }.also { handler.postDelayed(it, 2000) }
    }

    private fun cancelTimers() {
        stallCheck?.let(handler::removeCallbacks)
        stallCheck = null
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
        val layer = if (v === imageA.root) imageA else if (v === imageB.root) imageB else null
        if (layer != null) {
            stopAnimation(layer.drawable)
            layer.clear()
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

    /** ぼかし背景用に、画像をとても小さく（長い辺 32px）読み込む。拡大して表示するとぼけて見える */
    private fun loadBlur(uri: Uri): Bitmap? = try {
        val resolver = activity.contentResolver
        val small = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val r = 96f / max(info.size.width, info.size.height)
                if (r < 1f) decoder.setTargetSize(max(1, (info.size.width * r).toInt()), max(1, (info.size.height * r).toInt()))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 96) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
        small?.let {
            val r = 32f / max(it.width, it.height)
            Bitmap.createScaledBitmap(it, max(1, (it.width * r).toInt()), max(1, (it.height * r).toInt()), true)
        }
    } catch (t: Throwable) {
        null
    }

    private fun showMessage(text: String?) {
        if (text != null) PlayerStatus.message(zoneIndex, text)
        messageView.text = text
        messageView.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) messageView.bringToFront()
    }
}
