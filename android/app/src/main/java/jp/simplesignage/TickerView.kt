package jp.simplesignage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.view.Choreographer
import android.view.View
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** 画面の端を右から左へ流れるテロップ */
class TickerView(context: Context) : View(context) {
    /** 指定の回数を流し終えたとき */
    var onFinished: (() -> Unit)? = null
    var message: Ticker.Message? = null
        private set

    private val band = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private var offset = 0f
    private var passes = 0
    private var textWidth = 0f
    private var lastFrame = 0L
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val m = message ?: return
            val dt = if (lastFrame == 0L) 0f else (frameTimeNanos - lastFrame) / 1e9f
            lastFrame = frameTimeNanos
            // 1秒に 3.5 / 5 / 7 文字分進む（画面の幅や文の長さに関係なく読みやすい速さ）
            val charsPerSecond = when (m.speed) { 0 -> 3.5f; 2 -> 7f; else -> 5f }
            offset -= textPaint.textSize * charsPerSecond * dt
            if (offset < -textWidth) {
                passes++
                if (m.repeat in 1..passes) {
                    finish()
                    return
                }
                offset = width.toFloat()
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        visibility = GONE
    }

    fun show(m: Ticker.Message) {
        message = m
        band.color = when (m.style) {
            Ticker.STYLE_INFO -> 0xE61565C0.toInt()
            Ticker.STYLE_CALL -> 0xF0C62828.toInt()
            else -> 0xCC000000.toInt()
        }
        passes = 0
        visibility = VISIBLE
        requestLayout()
        post {
            textPaint.textSize = textSize(m)
            textWidth = textPaint.measureText(m.text)
            offset = width.toFloat()
            lastFrame = 0L
            if (!running) {
                running = true
                Choreographer.getInstance().postFrameCallback(frame)
            }
        }
    }

    fun stop() {
        running = false
        message = null
        visibility = GONE
    }

    private fun finish() {
        stop()
        onFinished?.invoke()
    }

    /** 表示中なら帯の高さ（時計などを重ならないようにずらすため） */
    val bandHeight: Int get() = if (visibility == VISIBLE && message != null) height else 0

    private fun textSize(m: Ticker.Message): Float {
        val dm = resources.displayMetrics
        val base = min(dm.widthPixels, dm.heightPixels)
        return base / when (m.size) { 0 -> 20f; 2 -> 9f; else -> 13f }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = message
        val h = if (m == null) 0 else (textSize(m) * 1.7f).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val m = message ?: return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), band)
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(m.text, offset, y, textPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }
}

/** 呼び出し音（ピンポーン）と日本語の読み上げ */
class Announcer(context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayDeque<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            ttsReady = status == TextToSpeech.SUCCESS &&
                engine.setLanguage(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE
            if (ttsReady) synchronized(pending) { while (pending.isNotEmpty()) speakNow(pending.removeFirst()) }
        }
    }

    /** チャイムを鳴らしてから読み上げる */
    fun announce(m: Ticker.Message) {
        thread(name = "announce", isDaemon = true) {
            if (m.chime) chime()
            if (m.speak) {
                if (ttsReady) speakNow(m.text) else synchronized(pending) { pending.addLast(m.text) }
            }
        }
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ticker-${System.nanoTime()}")
    }

    /** 2音（ミ→ド）のチャイムを合成して鳴らす（終わるまで待つ） */
    private fun chime() {
        val rate = 44100
        val notes = listOf(659.25 to 0.0, 523.25 to 0.55) // E5, C5
        val length = (rate * 1.7).toInt()
        val pcm = ShortArray(length)
        for ((freq, start) in notes) {
            val s0 = (start * rate).toInt()
            for (i in s0 until length) {
                val t = (i - s0).toDouble() / rate
                val env = exp(-t * 2.6) * min(1.0, t * 200) // 立ち上がりを滑らかに、減衰させる
                val v = env * (sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t))
                pcm[i] = (pcm[i] + (v * 9000).toInt()).coerceIn(-32767, 32767).toShort()
            }
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(1800)
            track.release()
        }
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
