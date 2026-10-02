package jp.signage.player

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 時刻サーバーへの問い合わせ（Windows 版の timesync.py と同じ）。端末の時計がずれていても、アプリの時刻を正しく保つ。
 * NTP（UDP 123）で調べ、UDP が塞がれたネットワークでは、HTTPS の応答の Date ヘッダー（精度は約 1 秒）で代わりに調べる。
 * 求めた「サーバーの時刻 − 端末の時刻」のずれ（ミリ秒）を、アプリの時刻に足す（端末のシステムの時計そのものは、変えない）。
 */
object TimeSync {
    private const val NTP_EPOCH = 2208988800L
    private val FALLBACK_URLS = listOf("https://www.google.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace")
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,98}[A-Za-z0-9])?$")

    fun validHost(host: String) = HOST.matches(host)

    private fun ntpMs(data: ByteArray, off: Int): Double {
        var sec = 0L
        var frac = 0L
        for (i in 0 until 4) sec = (sec shl 8) or (data[off + i].toLong() and 0xFF)
        for (i in 4 until 8) frac = (frac shl 8) or (data[off + i].toLong() and 0xFF)
        return (sec - NTP_EPOCH) * 1000.0 + frac * 1000.0 / 4294967296.0
    }

    /** NTP の応答から、ずれ（ミリ秒。サーバー − 端末）を求める。応答が正しくなければ例外 */
    fun parseNtp(data: ByteArray, t1: Double, t4: Double): Double {
        require(data.size >= 48) { "応答が短すぎます" }
        val mode = data[0].toInt() and 0x07
        val stratum = data[1].toInt() and 0xFF
        require(mode == 4 && stratum in 1..15) { "時刻サーバーの応答が正しくありません" }
        val t2 = ntpMs(data, 32)
        val t3 = ntpMs(data, 40)
        return ((t2 - t1) + (t3 - t4)) / 2
    }

    private fun sntp(host: String): Double {
        var last: Exception? = null
        for (addr in InetAddress.getAllByName(host).filterIsInstance<java.net.Inet4Address>().take(3)) {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 3000
                    val req = ByteArray(48).also { it[0] = 0x1B }
                    val t1 = System.currentTimeMillis().toDouble()
                    s.send(DatagramPacket(req, req.size, addr, 123))
                    val buf = ByteArray(512)
                    val res = DatagramPacket(buf, buf.size)
                    s.receive(res)
                    val t4 = System.currentTimeMillis().toDouble()
                    return parseNtp(buf.copyOf(res.length), t1, t4)
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("NTP で取得できません（${last?.javaClass?.simpleName ?: "応答なし"}）")
    }

    private fun httpDate(url: String): Double {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "SignagePlayer/1.0")
        }
        try {
            val t1 = System.currentTimeMillis().toDouble()
            conn.responseCode
            val t4 = System.currentTimeMillis().toDouble()
            val date = conn.getHeaderField("Date") ?: throw IllegalStateException("Date ヘッダーがありません")
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            val server = fmt.parse(date)!!.time + 500.0 // Date は秒の単位なので、秒の中ほどとみなす
            return server - (t1 + t4) / 2
        } finally {
            conn.disconnect()
        }
    }

    /** (ずれ（ミリ秒）, 方法)。NTP で取れなければ HTTPS で。どちらもだめなら例外 */
    fun measure(host: String): Pair<Long, String> {
        val errors = ArrayList<String>()
        if (validHost(host)) {
            try {
                return Math.round(sntp(host)) to "NTP（$host）"
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        for (url in FALLBACK_URLS) {
            try {
                return Math.round(httpDate(url)) to "HTTPS（Date ヘッダー・精度 約 1 秒）"
            } catch (e: Exception) {
                errors += "${URL(url).host}: ${e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException(errors.joinToString("；").ifEmpty { "取得できません" })
    }

    /** 時刻サーバーに問い合わせて、ずれを記録する（通信するので、バックグラウンドで呼ぶ）。失敗しても、前回のずれは、そのまま使う */
    fun syncNow(prefs: Prefs) {
        try {
            val (offset, method) = measure(prefs.timeServer.ifEmpty { "ntp.nict.jp" })
            prefs.timeSyncOffsetMs = offset
            prefs.timeSyncAt = System.currentTimeMillis()
            prefs.timeSyncMethod = method
            prefs.timeSyncError = ""
        } catch (e: Exception) {
            prefs.timeSyncError = (e.message ?: e.javaClass.simpleName).take(200)
        }
    }

    /** 1 時間ごと（失敗していれば 5 分ごと）に、問い合わせる時期か */
    fun due(prefs: Prefs): Boolean =
        prefs.timeSync && (prefs.timeSyncError.isNotEmpty() || System.currentTimeMillis() - prefs.timeSyncAt >= 3_600_000L)
}
