package jp.simplesignage

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * 管理画面のマイクから届いた声（16kHz・モノラル・16bit PCM）をすぐ再生する。
 * 届いた音を順に AudioTrack へ書き込む。再生画面が前面に無くても鳴る（管理用サービスが受け取るため）。
 */
object VoicePlayer {
    const val SAMPLE_RATE = 16000
    /** 再生を始める前に先に入れておく無音（ネットワークの揺れで途切れないための余裕） */
    private const val PREROLL_MS = 150

    private var track: AudioTrack? = null

    /** 届いた音を再生キューへ書き込む。書き込めなかったときは、理由つきで例外にする（管理画面にエラーとして表示される） */
    @Synchronized
    fun play(pcm: ByteArray) {
        val t = track ?: create().also { track = it }
        val n = t.write(pcm, 0, pcm.size)
        if (n < 0) {
            release()
            throw IllegalStateException("音声を再生できません（AudioTrack エラー $n）")
        }
    }

    @Synchronized
    fun release() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private fun create(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, SAMPLE_RATE * 2 / 2) // 0.5 秒分
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("音声の出力を初期化できません")
        }
        t.write(ByteArray(SAMPLE_RATE * 2 * PREROLL_MS / 1000), 0, SAMPLE_RATE * 2 * PREROLL_MS / 1000)
        t.play()
        return t
    }
}
