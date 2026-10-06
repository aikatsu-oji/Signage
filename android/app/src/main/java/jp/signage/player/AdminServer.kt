package jp.signage.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 同じネットワーク内の PC・スマホのブラウザから操作する管理画面のサーバー。
 * 画像・動画のアップロード／削除、設定の変更、再生内容の即時反映ができる。
 *
 * - 同じ LAN（プライベートアドレス）からの接続だけを受け付ける
 * - /api/ はすべて PIN（設定画面に表示）が必要。続けて間違えると一定時間ロックする
 */
object AdminServer {
    const val DEFAULT_PORT = 8080
    private const val HEADER_TIMEOUT_MS = 10_000L
    private const val MAX_TRACKED_IPS = 256
    private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
    private const val DISK_RESERVE_BYTES = 200L * 1024 * 1024
    private const val STRIKE_FORGET_MS = 24 * 3_600_000L

    /** 再生側に知らせるイベント */
    const val EVENT_CONTENT = "content"   // ファイルが変わった → 読み直す
    const val EVENT_SETTINGS = "settings" // 設定が変わった → 画面を作り直す
    const val EVENT_SERVER = "server"     // サーバーの起動・停止
    const val EVENT_VOICE_START = "voice-start" // 管理画面からの声の放送が始まった → 動画の音を下げ、「放送中」を表示
    const val EVENT_VOICE_END = "voice-end"     // 放送が終わった

    private lateinit var app: Context
    private var server: ServerSocket? = null
    /** 設定で有効になっているか（起動中に無効にされた場合に備える） */
    @Volatile private var wanted = false
    private var starting = false
    @Volatile var port = 0
        private set

    val isRunning get() = server != null && port > 0

    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    /** PIN・グループコードの失敗は、接続元の IP ごとに数える（1 台の悪意ある端末が、本物の管理者まで締め出さないように） */
    private class Strikes { var failures = 0; var rounds = 0; var lockedUntil = 0L; var last = 0L }
    private val strikes = HashMap<String, Strikes>()

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
    private fun notify(event: String) = main.post { listeners.forEach { it(event) } }

    /** 声の放送中か。放送が途切れたら、一定時間後に終わらせる */
    @Volatile private var voiceActive = false
    private val voiceIdle = Runnable {
        VoicePlayer.release()
        if (voiceActive) {
            voiceActive = false
            notify(EVENT_VOICE_END)
        }
    }

    /** 設定に合わせて起動・停止する（何度呼んでもよい） */
    @Synchronized
    fun update(context: Context) {
        app = context.applicationContext
        wanted = Prefs(app).adminEnabled
        if (wanted) start() else stop()
    }

    private fun start() {
        if (server != null || starting) return
        starting = true
        thread(name = "admin-server", isDaemon = true) {
            // HTTPS が ON なら、自己署名の証明書で暗号化する。用意できなければ HTTP のまま続ける
            // 使用中なら次の番号を試す（bind に失敗したソケットは閉じられるので毎回作り直す）
            val bound = (DEFAULT_PORT until DEFAULT_PORT + 10).firstNotNullOfOrNull { p ->
                runCatching {
                    ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                }.getOrNull()
            }
            val s = synchronized(this) {
                starting = false
                if (bound != null && wanted) {
                    server = bound
                    port = bound.localPort
                    bound
                } else {
                    bound?.close()
                    null
                }
            }
            if (s == null) {
                notify(EVENT_SERVER)
                return@thread
            }
            notify(EVENT_SERVER)
            Peers.start(app, s.localPort)
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break } catch (e: Throwable) { continue }
                // LAN の外からの接続は、何も読まずにすぐ切る（接続を占有されないように）
                if (!isLan(client.inetAddress)) { runCatching { client.close() }; continue }
                try {
                    pool.execute {
                        // 通信まわりの例外でアプリごと落ちないよう、すべてここで受け止める
                        try { handle(client) } catch (e: Throwable) { runCatching { client.close() } }
                    }
                } catch (e: Throwable) { runCatching { client.close() } }
            }
        }
    }

    /** 設定（HTTPS など）が変わったとき、サーバーを起動し直す */
    @Synchronized
    fun restart(context: Context) {
        app = context.applicationContext
        stop()
        update(context)
    }

    private fun stop() {
        Peers.stop()
        server?.let { runCatching { it.close() } }
        server = null
        port = 0
        notify(EVENT_SERVER)
    }

    /** この端末の画面サイズ（px）。画像がどう収まるかを管理画面で確認するために使う */
    @Suppress("DEPRECATION")
    private fun screenSize(): JSONObject {
        val dm = android.util.DisplayMetrics()
        runCatching {
            (app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(dm)
        }
        // 「画面を回す」が有効なとき（テレビ）は、再生画面を回して表示するので縦横を入れ替えて返す
        val swap = PlayerActivity.needsSoftRotation(app, Prefs(app))
        return JSONObject().put("width", if (swap) dm.heightPixels else dm.widthPixels)
            .put("height", if (swap) dm.widthPixels else dm.heightPixels)
    }

    /** この端末の LAN 内の IPv4 アドレス（管理画面の URL 表示用） */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- HTTP

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
    ) {
        val contentLength = (headers["content-length"]?.toLongOrNull() ?: 0L).also {
            if (it < 0) throw HttpError(400, "不正なリクエスト")
        }

        fun readText(limit: Int = 64 * 1024): String {
            if (contentLength > limit) throw HttpError(413, "データが大きすぎます")
            val buf = ByteArray(contentLength.toInt())
            var off = 0
            while (off < buf.size) {
                val n = body.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }

        fun json() = JSONObject(readText().ifEmpty { "{}" })

        fun readBytes(limit: Int): ByteArray {
            if (contentLength > limit) throw HttpError(413, "データが大きすぎます")
            val buf = ByteArray(contentLength.toInt())
            var off = 0
            while (off < buf.size) {
                val n = body.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return if (off == buf.size) buf else buf.copyOf(off)
        }
    }

    private class Response(
        val status: Int,
        val type: String,
        val length: Long,
        val extraHeaders: Map<String, String> = emptyMap(),
        val write: (OutputStream) -> Unit,
    )

    private class HttpError(val status: Int, message: String) : Exception(message)

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())
            var cors: Map<String, String> = emptyMap()
            val response = try {
                val req = readRequest(input) ?: return
                cors = corsHeaders(req)
                // curl などは大きな送信の前に確認を求めるので、続けてよいと返す
                if (req.headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    out.flush()
                }
                route(req, s.inetAddress)
            } catch (e: HttpError) {
                json(e.status, JSONObject().put("error", e.message))
            } catch (e: Exception) {
                json(500, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
            }
            runCatching {
                val reason = when (response.status) {
                    200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
                    404 -> "Not Found"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Error"
                }
                val head = StringBuilder()
                    .append("HTTP/1.1 ${response.status} $reason\r\n")
                    .append("Content-Type: ${response.type}\r\n")
                    .append("Content-Length: ${response.length}\r\n")
                    .append("Cache-Control: no-store\r\n")
                    .append("Connection: close\r\n")
                (response.extraHeaders + cors).forEach { (k, v) -> head.append("$k: $v\r\n") }
                head.append("\r\n")
                out.write(head.toString().toByteArray(Charsets.UTF_8))
                response.write(out)
                out.flush()
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        // ヘッダーを空行まで読む（最大 16KB）
        val buf = ByteArrayOutputStream()
        var last4 = 0
        // 1 バイトずつ細々と送って接続を占有する攻撃（slowloris）を避けるため、ヘッダー全体に期限を設ける
        val deadline = System.currentTimeMillis() + HEADER_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) throw HttpError(400, "リクエストの受信に時間がかかりすぎました")
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (buf.size() > 16 * 1024) throw HttpError(400, "ヘッダーが大きすぎます")
        }
        val lines = buf.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) throw HttpError(400, "不正なリクエスト")
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val target = parts[1]
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        return Request(parts[0].uppercase(), decode(target.substringBefore('?')), query, headers, input)
    }

    private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")

    // ---------------------------------------------------------------- ルーティング

    /**
     * 別の端末の管理画面（同じ LAN 内のアドレスの http ページ）からの操作を許可する。
     * インターネット上のページからは許可しない。
     */
    private fun corsHeaders(req: Request): Map<String, String> {
        val origin = req.headers["origin"] ?: return emptyMap()
        if (!isLanOrigin(origin)) return emptyMap()
        val headers = mutableMapOf(
            "Access-Control-Allow-Origin" to origin,
            "Vary" to "Origin",
            "Access-Control-Allow-Headers" to "X-Pin, X-Group-Code, Content-Type",
            "Access-Control-Allow-Methods" to "GET, POST, PUT, OPTIONS",
            "Access-Control-Max-Age" to "600",
        )
        // Chrome の Private Network Access の確認
        if (req.headers["access-control-request-private-network"] == "true") {
            headers["Access-Control-Allow-Private-Network"] = "true"
        }
        return headers
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private fun isLanOrigin(origin: String): Boolean {
        val uri = Uri.parse(origin)
        if (uri.scheme != "http") return false
        val host = uri.host ?: return false
        if (host == "localhost") return true
        if (!IPV4.matches(host)) return false // 名前解決はしない
        return runCatching { isLan(InetAddress.getByName(host)) }.getOrDefault(false)
    }

    private fun route(req: Request, from: InetAddress): Response {
        if (!isLan(from)) throw HttpError(403, "同じネットワーク内からのみ利用できます")
        // 偽のドメイン名を使った攻撃（DNS リバインディング）と、外部のサイトからの操作（CSRF）を拒否する
        if (!RequestGuard.hostOk(req.headers["host"])) throw HttpError(403, "このアドレスでは利用できません")
        req.headers["origin"]?.let { if (!isLanOrigin(it)) throw HttpError(403, "許可されていない接続元です") }
        if (req.method == "OPTIONS") {
            val origin = req.headers["origin"]
            if (origin == null || !isLanOrigin(origin)) throw HttpError(403, "許可されていない接続元です")
            return Response(204, "text/plain", 0) {}
        }

        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            val html = app.assets.open("admin.html").use { it.readBytes() }
            return Response(
                200, "text/html; charset=utf-8", html.size.toLong(),
                // 他のページに埋め込まれて操作されるのを防ぐ
                mapOf("X-Frame-Options" to "DENY", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer"),
            ) { it.write(html) }
        }
        if (!req.path.startsWith("/api/")) throw HttpError(404, "見つかりません")
        val prefs = Prefs(app)
        // グループコードが未設定の端末は、この端末自身からしか操作できない
        if (prefs.groupCode.isEmpty() && !MacAccess.isLoopback(from)) {
            throw HttpError(403, "グループコードが未設定のため、この端末自身からしか操作できません。この端末の設定画面で、グループコードを決めてください")
        }
        // 操作できる端末の制限（MAC アドレス）。この端末が MAC アドレスを調べられないときは、締め出さないよう制限しない
        if (prefs.macLock && MacAccess.canResolve()) {
            val why = MacAccess.gate(true, prefs.allowedMacs, prefs.allowVpn, from, MacAccess.lookup(from))
            if (why.isNotEmpty()) throw HttpError(403, why)
        }
        checkPin(from, req.headers["x-pin"], req.headers["x-group-code"])

        return when ("${req.method} ${req.path}") {
            "GET /api/state" -> json(200, state(prefs).put("access", accessInfo(prefs, from)).put("group", groupInfo(prefs)))
            "GET /api/status" -> json(200, status(prefs))
            "PUT /api/apk" -> receiveApk(prefs, req)
            "GET /api/weather/offices" -> {
                val arr = org.json.JSONArray()
                Weather.offices(app).forEach { o ->
                    arr.put(JSONObject().put("code", o.code).put("name", o.name)
                        .put("areas", org.json.JSONArray().also { a -> o.areas.forEach { (c, n) -> a.put(org.json.JSONArray().put(c).put(n)) } })
                        .put("cities", org.json.JSONArray().also { a -> o.cities.forEach { c ->
                            a.put(JSONObject().put("code", c.code).put("name", c.name).put("areaCode", c.areaCode).put("areaName", c.areaName))
                        } }))
                }
                val bytes = arr.toString().toByteArray(Charsets.UTF_8)
                Response(200, "application/json; charset=utf-8", bytes.size.toLong()) { it.write(bytes) }
            }
            "POST /api/timesync" -> {
                TimeSync.syncNow(prefs) // すぐに、時刻サーバーに問い合わせる（数秒かかることがある）
                json(200, timeSyncInfo(prefs).put("appTime", AppTime.localDateTime(prefs).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
            }
            "POST /api/group" -> {
                val code = req.json().optString("code", "").trim()
                if (code.isNotEmpty() && !GroupCode.valid(code)) throw HttpError(400, "グループコードは、英数字と - _ だけの 8〜32 文字にしてください")
                prefs.groupCode = code
                Peers.start(app, port) // 見つけ合いの識別子を変えて、一覧を新しいグループで作り直す
                json(200, groupInfo(prefs))
            }
            "POST /api/access" -> json(200, applyAccess(prefs, req.json(), from))
            "PUT /api/upload" -> {
                val zone = req.query["zone"]?.takeIf { it.isNotEmpty() && it != "-1" }?.let { zoneIndex(prefs, it) } // 区画なし＝ライブラリにだけ保存
                val name = Library.sanitize(req.query["name"] ?: "")
                    ?: throw HttpError(400, "画像・動画のファイルのみアップロードできます")
                if (req.contentLength <= 0) throw HttpError(400, "ファイルが空です")
                if (req.contentLength > MAX_UPLOAD_BYTES) throw HttpError(413, "ファイルが大きすぎます（4GB まで）")
                // 空き容量を使い切らない
                val free = runCatching { Library.dir(app).usableSpace }.getOrDefault(Long.MAX_VALUE)
                if (req.contentLength > free - DISK_RESERVE_BYTES) throw HttpError(413, "空き容量が足りません")
                val saved = Library.upload(app, zone, name, req.contentLength, req.body)
                notify(EVENT_CONTENT)
                json(200, JSONObject().put("ok", true).put("name", saved))
            }
            "POST /api/delete" -> {
                val body = req.json()
                val zone = zoneIndex(prefs, body.optString("zone"))
                try { Library.find(app, zone, body.getString("name")) } catch (e: IOException) { throw HttpError(404, e.message ?: "ファイルが見つかりません") }
                Library.remove(app, zone, body.getString("name"))
                notify(EVENT_CONTENT)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/filerotate" -> {
                val body = req.json()
                val degrees = body.optInt("degrees", 0)
                if (degrees !in listOf(0, 90, 180, 270)) throw HttpError(400, "回転は 0・90・180・270 のどれかにしてください")
                val name = body.getString("name")
                try { Library.libraryFile(app, name) } catch (e: IOException) { throw HttpError(404, e.message ?: "ファイルが見つかりません") }
                prefs.setFileRotation(name, degrees)
                notify(EVENT_CONTENT)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/filerule" -> { // 旧 API：区画のその名前の配置すべてに、同じ条件を設定する
                val body = req.json()
                val zone = zoneIndex(prefs, body.optString("zone"))
                val rule = try {
                    FileRule.normalize(body.optJSONObject("rule"))
                } catch (e: IllegalArgumentException) {
                    throw HttpError(400, e.message ?: "条件が正しくありません")
                }
                val name = body.getString("name")
                prefs.placementsOf(zone).filter { it.name == name }.forEach {
                    Library.updatePlacement(app, it.id, JSONObject().put("rule", rule ?: JSONObject.NULL))
                }
                notify(EVENT_CONTENT)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/placement" -> placementOp(prefs, req.json())
            "POST /api/library/delete" -> {
                val name = req.json().getString("name")
                try { Library.libraryFile(app, name) } catch (e: IOException) { throw HttpError(404, e.message ?: "ファイルが見つかりません") }
                Library.deleteLibraryFile(app, name)
                notify(EVENT_CONTENT)
                json(200, JSONObject().put("ok", true))
            }
            "GET /api/file" -> {
                val name = req.query["name"] ?: ""
                val (stream, size) = try { Library.openFile(app, name) } catch (e: IOException) { throw HttpError(404, e.message ?: "ファイルが見つかりません") }
                val type = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                Response(200, type, size) { out -> stream.use { it.copyTo(out, 256 * 1024) } }
            }
            "GET /api/devices" -> {
                val list = JSONArray()
                Peers.list().forEach {
                    list.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("version", it.version))
                }
                val hidden = JSONArray()
                Peers.listHidden().forEach { hidden.put(JSONObject().put("name", it.name).put("reason", it.reason)) }
                json(200, JSONObject().put("id", prefs.deviceId).put("name", prefs.deviceName).put("peers", list).put("hidden", hidden))
            }
            "POST /api/pin" -> {
                if (!prefs.setAdminPin(req.json().optString("pin"))) throw HttpError(400, "PIN は6桁の数字にしてください")
                prefs.bumpSettingsVersion()
                notify(EVENT_SETTINGS)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/name" -> {
                val name = req.json().optString("name").trim()
                if (name.isEmpty()) throw HttpError(400, "端末名を入力してください")
                prefs.deviceName = name
                prefs.bumpSettingsVersion()
                Peers.start(app, port) // 新しい名前で登録し直す
                notify(EVENT_SETTINGS)
                json(200, JSONObject().put("ok", true).put("name", prefs.deviceName))
            }
            "POST /api/ticker" -> {
                val m = Ticker.Message.fromJson(req.json())
                if (m.text.isEmpty()) throw HttpError(400, "テロップの文字を入力してください")
                Ticker.post(app, m.copy(text = m.text.replace(Regex("\\s*\\n\\s*"), "　")))
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/ticker/stop" -> {
                Ticker.stop(app)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/ticker/schedules" -> {
                val a = req.json().optJSONArray("schedules") ?: JSONArray()
                val list = (0 until a.length()).map {
                    Ticker.Schedule.fromJson(a.getJSONObject(it)) ?: throw HttpError(400, "予約の時刻や文字が正しくありません")
                }
                Ticker.setSchedules(app, list)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/voice" -> {
                // 管理画面のマイクの声（16kHz・モノラル・16bit PCM、0.2秒ぶんほど）。届いた順にすぐ再生する
                val pcm = req.readBytes(64 * 1024)
                if (pcm.size < 2 || pcm.size % 2 != 0) throw HttpError(400, "音声データが正しくありません")
                if (!voiceActive) {
                    voiceActive = true
                    notify(EVENT_VOICE_START)
                }
                VoicePlayer.play(pcm)
                // 途切れたとき（ブラウザを閉じた等）に、音量を元に戻して終わる
                main.removeCallbacks(voiceIdle)
                main.postDelayed(voiceIdle, 2500)
                // 管理画面の診断表示用：受け取ったバイト数と、端末のメディア音量（0 だと音が出ない）
                val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                json(200, JSONObject().put("ok", true).put("bytes", pcm.size)
                    .put("volume", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                    .put("volumeMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)))
            }
            "POST /api/voice/end" -> {
                main.removeCallbacks(voiceIdle)
                main.postDelayed(voiceIdle, 800) // 再生中の音を聞き終わってから止める
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/reload" -> {
                notify(EVENT_CONTENT)
                json(200, JSONObject().put("ok", true))
            }
            "POST /api/settings" -> {
                applySettings(prefs, req.json())
                prefs.bumpSettingsVersion()
                notify(EVENT_SETTINGS)
                json(200, JSONObject().put("ok", true))
            }
            else -> throw HttpError(404, "見つかりません")
        }
    }

    private fun timeSyncInfo(prefs: Prefs) = JSONObject()
        .put("enabled", prefs.timeSync).put("server", prefs.timeServer).put("offsetMs", prefs.timeSyncOffsetMs)
        .put("at", prefs.timeSyncAt).put("method", prefs.timeSyncMethod).put("error", prefs.timeSyncError)

    private fun groupInfo(prefs: Prefs) =
        JSONObject().put("enabled", prefs.groupCode.isNotEmpty()).put("id", GroupCode.ident(prefs.groupCode))


    /** 管理画面の URL（この端末の IP アドレス用）の頭の部分 */
    val scheme get() = "http"

    /** 配信状況（管理画面のモニタリング用） */
    private fun status(prefs: Prefs): JSONObject {
        val zones = JSONArray()
        if (PlayerStatus.running) {
            PlayerStatus.zones.toSortedMap().forEach { (i, z) ->
                zones.put(JSONObject().put("zone", i).put("kind", z.kind).put("name", z.name).put("video", z.video)
                    .put("pos", z.pos).put("total", z.total).put("paused", false).put("message", z.message))
            }
        }
        val disk = runCatching {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            val st = android.os.StatFs(dir.path)
            JSONObject().put("free", st.availableBytes).put("total", st.totalBytes)
        }.getOrNull()
        return JSONObject()
            .put("id", prefs.deviceId).put("name", prefs.deviceName).put("version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "")
            .put("time", System.currentTimeMillis())
            .put("uptimeSec", (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000)
            .put("player", JSONObject().put("running", PlayerStatus.running).put("age", JSONObject.NULL).put("zones", zones)
                .put("issues", JSONArray(PlayerStatus.issues())))
            .put("ticker", JSONObject().put("standing", Ticker.standing(app) != null).put("queued", 0))
            .put("disk", disk ?: JSONObject.NULL)
            .put("platform", "android")
            .put("update", JSONObject().put("allowed", prefs.allowRemoteUpdate).put("canInstall", AppUpdater.canInstall(app))
                .put("phase", AppUpdater.state.phase).put("message", AppUpdater.state.message).put("at", AppUpdater.state.at))
    }

    /** 管理画面から送られた APK を受け取り、更新を始める */
    private fun receiveApk(prefs: Prefs, req: Request): Response {
        if (!prefs.allowRemoteUpdate) {
            throw HttpError(403, "この端末は、管理画面からのアプリ更新を許可していません。端末の設定画面で「管理画面からのアプリ更新を許可」を ON にしてください")
        }
        val len = req.contentLength
        if (len <= 0) throw HttpError(400, "ファイルが空です")
        if (len > AppUpdater.MAX_BYTES) throw HttpError(413, "ファイルが大きすぎます")
        val f = AppUpdater.apkFile(app)
        f.delete()
        try {
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = req.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw HttpError(400, "ファイルを最後まで受け取れませんでした")
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        } catch (e: IOException) {
            f.delete()
            throw HttpError(400, "ファイルを受け取れませんでした")
        }
        AppUpdater.state = AppUpdater.State("received", "APK を受け取りました。確認中…")
        val why = AppUpdater.validate(app, f)
        if (why != null) {
            f.delete()
            AppUpdater.state = AppUpdater.State("failed", why)
            throw HttpError(400, why)
        }
        AppUpdater.install(app, f)
        return json(200, JSONObject().put("ok", true).put("phase", AppUpdater.state.phase).put("message", AppUpdater.state.message))
    }

    private fun accessInfo(prefs: Prefs, from: InetAddress): JSONObject {
        val you = JSONObject().put("ip", from.hostAddress?.substringBefore('%'))
            .put("vpn", MacAccess.isVpn(from)).put("local", MacAccess.isLoopback(from))
        you.put("mac", MacAccess.lookup(from) ?: JSONObject.NULL)
        return JSONObject()
            .put("enabled", prefs.macLock).put("allowVpn", prefs.allowVpn)
            .put("devices", MacAccess.toJson(prefs.allowedMacs))
            .put("canResolve", MacAccess.canResolve()).put("you", you)
    }

    private fun applyAccess(prefs: Prefs, j: JSONObject, from: InetAddress): JSONObject {
        if (!MacAccess.canResolve()) {
            throw HttpError(400, "この端末は、接続してきた端末の MAC アドレスを確認できないため、制限は使えません")
        }
        val devs = try {
            if (j.has("devices")) MacAccess.clean(j.getJSONArray("devices")) else prefs.allowedMacs
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, e.message ?: "端末の一覧が正しくありません")
        }
        val lock = if (j.has("enabled")) j.getBoolean("enabled") else prefs.macLock
        val vpn = if (j.has("allowVpn")) j.getBoolean("allowVpn") else prefs.allowVpn
        val why = MacAccess.checkUpdate(lock, devs, vpn, from, MacAccess.lookup(from))
        if (why.isNotEmpty()) throw HttpError(400, why)
        prefs.macLock = lock
        prefs.allowVpn = vpn
        prefs.allowedMacs = devs
        return accessInfo(prefs, from)
    }

    private fun isLan(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
        is Inet6Address -> a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
            (a.address[0].toInt() and 0xFE) == 0xFC || // ユニークローカル fc00::/7
            (a.isIPv4CompatibleAddress && isLan(InetAddress.getByAddress(a.address.copyOfRange(12, 16))))
        else -> false
    }

    @Synchronized
    private fun checkPin(from: InetAddress, pin: String?, groupCode: String?) {
        val now = System.currentTimeMillis()
        val key = from.hostAddress ?: ""
        // 長く来ていない接続元の記録は捨てる（記録が増え続けないように）
        if (strikes.size > MAX_TRACKED_IPS) strikes.entries.removeAll { now - it.value.last > STRIKE_FORGET_MS }
        val st = strikes.getOrPut(key) { Strikes() }
        st.last = now
        if (now < st.lockedUntil) throw HttpError(429, "PIN またはグループコードを続けて間違えたため、しばらく操作できません")
        val prefs = Prefs(app)
        // グループコード（設定している端末だけ）と PIN を確かめる。どちらを間違えても、失敗の回数に数える
        val expectedGroup = prefs.groupCode
        val groupOk = expectedGroup.isEmpty() ||
            (groupCode != null && MessageDigest.isEqual(groupCode.toByteArray(), expectedGroup.toByteArray()))
        val pinOk = pin != null && MessageDigest.isEqual(pin.toByteArray(), prefs.adminPin.toByteArray())
        if (groupOk && pinOk) {
            strikes.remove(key)
            return
        }
        st.failures++
        if (st.failures >= 5) {
            // 5 回間違えるたびに、待ち時間を 1 分 → 5 分 → 25 分 → 1 時間 と延ばす
            st.failures = 0
            st.lockedUntil = now + minOf(60_000L * Math.pow(5.0, st.rounds.toDouble()).toLong(), 3_600_000L)
            st.rounds++
        }
        // グループコードを先に判定する（コードを知らない相手に、PIN が合っているかを教えない）
        throw HttpError(401, if (groupOk) "PIN が違います" else "グループコードが違います")
    }

    private fun zoneIndex(prefs: Prefs, raw: String?): Int {
        val i = raw?.toIntOrNull() ?: throw HttpError(400, "区画が指定されていません")
        if (i !in 0 until Prefs.zoneCount(prefs.layout) || prefs.zoneType(i) != Prefs.ZONE_FOLDER) {
            throw HttpError(400, "フォルダの区画ではありません")
        }
        return i
    }

    // ---------------------------------------------------------------- ライブラリと配置

    private fun placementJson(p: Prefs.Placement) = JSONObject().put("id", p.id).put("zone", p.zone).put("name", p.name).apply {
        p.rule?.let { put("rule", it) }
        if (p.exclusive) put("exclusive", true)
        p.seconds?.let { put("seconds", it) }
    }

    private fun placementsJson(prefs: Prefs) = JSONArray(prefs.placements().map(::placementJson))

    private fun libraryJson() = JSONArray(runCatching { Library.items(app) }.getOrDefault(emptyList()).map { f ->
        JSONObject().put("name", f.name).put("size", f.size).put("video", f.video).apply {
            if (f.rotation != 0) put("rotation", f.rotation)
            put("placements", JSONArray(f.placements.map { (id, zone) -> JSONObject().put("id", id).put("zone", zone) }))
        }
    })

    /** 配置の追加・変更・削除・並べ替え（管理画面のスケジュール画面・ライブラリ画面が使う） */
    private fun placementOp(prefs: Prefs, j: JSONObject): Response {
        try {
            when (j.optString("op")) {
                "add" -> {
                    val zone = zoneIndex(prefs, j.optString("zone"))
                    val name = j.getString("name")
                    try { Library.libraryFile(app, name) } catch (e: IOException) { throw HttpError(404, e.message ?: "ファイルが見つかりません") }
                    val rule = FileRule.normalize(j.optJSONObject("rule"))
                    val seconds = if (j.has("seconds") && !j.isNull("seconds")) j.optInt("seconds", 0).takeIf { it > 0 }?.coerceIn(1, 3600) else null
                    val id = Library.addPlacement(app, zone, name, rule, j.optBoolean("exclusive", false), seconds)
                    notify(EVENT_CONTENT)
                    return json(200, JSONObject().put("ok", true).put("id", id))
                }
                "update" -> {
                    val fields = JSONObject()
                    for (k in listOf("rule", "exclusive", "seconds", "zone")) if (j.has(k)) fields.put(k, j.get(k))
                    if (fields.has("zone")) zoneIndex(prefs, fields.get("zone").toString())
                    if (!Library.updatePlacement(app, j.optString("id"), fields)) throw HttpError(404, "見つかりません")
                }
                "remove" -> Library.removePlacementById(app, j.optString("id"))
                "reorder" -> {
                    val zone = zoneIndex(prefs, j.optString("zone"))
                    val arr = j.optJSONArray("ids") ?: JSONArray()
                    Library.reorder(app, zone, (0 until arr.length()).map { arr.optString(it) })
                }
                else -> throw HttpError(400, "操作が正しくありません")
            }
        } catch (e: IllegalArgumentException) {
            throw HttpError(400, e.message ?: "指定が正しくありません")
        }
        notify(EVENT_CONTENT)
        return json(200, JSONObject().put("ok", true))
    }

    // ---------------------------------------------------------------- 状態と設定

    private fun state(prefs: Prefs): JSONObject {
        val names = Prefs.zoneNames(prefs.layout)
        val zones = JSONArray()
        for (i in 0 until Prefs.zoneCount(prefs.layout)) {
            val type = prefs.zoneType(i)
            val z = JSONObject()
                .put("index", i)
                .put("label", "区画${i + 1}（${names.getOrElse(i) { "" }}）")
                .put("type", type)
            if (type == Prefs.ZONE_WEB || type == Prefs.ZONE_RSS) {
                z.put("url", prefs.zoneUrl(i)).put("refreshMin", prefs.zoneRefreshMin(i))
            }
            if (type == Prefs.ZONE_FOLDER) {
                z.put("folder", "ライブラリ（アプリ専用の保存場所）").put("writable", true)
                val files = JSONArray()
                runCatching { Library.entries(app, i) }
                    .onFailure { z.put("error", "画像・動画を読み込めません") }
                    .getOrDefault(emptyList())
                    .forEach {
                        files.put(JSONObject().put("id", it.id).put("name", it.name).put("video", it.isVideo).put("size", it.size).apply {
                            it.rule?.let { r -> put("rule", r) }
                            if (it.exclusive) put("exclusive", true)
                            it.seconds?.let { s -> put("seconds", s) }
                            if (it.rotation != 0) put("rotation", it.rotation)
                        })
                    }
                z.put("files", files)
            }
            zones.put(z)
        }
        val settings = JSONObject()
            .put("layout", prefs.layout)
            .put("splitPercent", prefs.splitPercent)
            .put("splitA", prefs.splitA)
            .put("splitB", prefs.splitB)
            .put("mainPercent", prefs.mainPercent)
            .put("sidePercent", prefs.sidePercent)
            .put("zoneTypes", JSONArray((0 until Prefs.MAX_ZONES).map(prefs::zoneType)))
            .put("zoneUrls", JSONArray((0 until Prefs.MAX_ZONES).map(prefs::zoneUrl)))
            .put("zoneRefreshMin", JSONArray((0 until Prefs.MAX_ZONES).map(prefs::zoneRefreshMin)))
            .put("imageSeconds", prefs.imageSeconds)
            .put("shuffle", prefs.shuffle)
            .put("recursive", prefs.recursive)
            .put("videoSound", prefs.videoSound)
            .put("videoCompat", prefs.videoCompat)
            .put("videoMultiSoft", prefs.videoMultiSoft)
            .put("fitMode", prefs.fitMode)
            .put("screenRotate", prefs.screenRotate)
            .put("screenRotateSupported", PlayerActivity.isTv(app))
            .put("orientation", when (prefs.orientation) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> 1
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> 2
                else -> 0
            })
            .put("clockEnabled", prefs.clockEnabled)
            .put("clockPosition", prefs.clockPosition)
            .put("clockSize", prefs.clockSize)
            .put("timeZone", prefs.timeZone)
            .put("timeOffsetSec", prefs.timeOffsetSec)
            .put("timeFormat", prefs.timeFormat)
            .put("timeSync", prefs.timeSync)
            .put("timeServer", prefs.timeServer)
            .put("timeSyncInfo", timeSyncInfo(prefs))
            .put("appTime", AppTime.localDateTime(prefs).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .put("weatherEnabled", prefs.weatherEnabled)
            .put("weatherIntervalMin", prefs.weatherIntervalMin)
            .put("weatherSeconds", prefs.weatherSeconds)
            .put("weatherTimeSeries", prefs.weatherTimeSeries)
            .put("weatherPlace", prefs.weatherCityName ?: prefs.weatherAreaName ?: "")
            .put("weatherOffice", prefs.weatherOffice ?: JSONObject.NULL)
            .put("weatherArea", prefs.weatherArea ?: JSONObject.NULL)
            .put("weatherAreaName", prefs.weatherAreaName ?: JSONObject.NULL)
            .put("weatherCity", prefs.weatherCity ?: JSONObject.NULL)
            .put("weatherCityName", prefs.weatherCityName ?: JSONObject.NULL)
            .put("weatherRegions", org.json.JSONArray(prefs.weatherRegions))
            .put("weatherRegionDay", prefs.weatherRegionDay)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val ticker = JSONObject()
            .put("standing", Ticker.standing(app)?.toJson() ?: JSONObject.NULL)
            .put("schedules", JSONArray(Ticker.schedules(app).map { it.toJson() }))
        return JSONObject()
            .put("ticker", ticker)
            .put("library", libraryJson())
            .put("placements", placementsJson(prefs))
            .put("id", prefs.deviceId)
            .put("name", prefs.deviceName)
            .put("device", android.os.Build.MODEL)
            .put("screen", screenSize())
            .put("version", version ?: "")
            .put("zones", zones)
            .put("settings", settings)
    }

    private fun applySettings(prefs: Prefs, j: JSONObject) {
        if (j.has("screenRotate") && PlayerActivity.isTv(app)) prefs.screenRotate = j.getInt("screenRotate")
        if (j.has("layout")) prefs.layout = j.getInt("layout").coerceIn(0, 5)
        // 画面の向き（0=端末の向きに従う / 1=横向きに固定 / 2=縦向きに固定）
        if (j.has("orientation")) {
            prefs.orientation = when (j.getInt("orientation")) {
                1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (j.has("splitPercent")) prefs.splitPercent = j.getInt("splitPercent")
        if (j.has("splitA")) prefs.splitA = j.getInt("splitA")
        if (j.has("splitB")) prefs.splitB = j.getInt("splitB")
        if (j.has("mainPercent")) prefs.mainPercent = j.getInt("mainPercent")
        if (j.has("sidePercent")) prefs.sidePercent = j.getInt("sidePercent")
        j.optJSONArray("zoneTypes")?.let { a ->
            for (i in 0 until minOf(a.length(), Prefs.MAX_ZONES)) {
                val t = a.getInt(i)
                if (t in Prefs.ZONE_FOLDER..Prefs.ZONE_RSS) prefs.setZoneType(i, t)
            }
        }
        j.optJSONArray("zoneUrls")?.let { a ->
            for (i in 0 until minOf(a.length(), Prefs.MAX_ZONES)) {
                val v = a.optString(i, "").trim()
                if (v.isNotEmpty()) {
                    val why = ZoneUrl.check(v)
                    if (why.isNotEmpty()) throw HttpError(400, "区画${i + 1}：$why")
                }
                prefs.setZoneUrl(i, v)
            }
        }
        j.optJSONArray("zoneRefreshMin")?.let { a ->
            for (i in 0 until minOf(a.length(), Prefs.MAX_ZONES)) prefs.setZoneRefreshMin(i, a.optInt(i, 10))
        }
        if (j.has("imageSeconds")) prefs.imageSeconds = j.getInt("imageSeconds")
        if (j.has("shuffle")) prefs.shuffle = j.getBoolean("shuffle")
        if (j.has("recursive")) prefs.recursive = j.getBoolean("recursive")
        if (j.has("videoSound")) prefs.videoSound = j.getBoolean("videoSound")
        if (j.has("videoCompat")) prefs.videoCompat = j.getBoolean("videoCompat")
        if (j.has("videoMultiSoft")) prefs.videoMultiSoft = j.getBoolean("videoMultiSoft")
        if (j.has("fitMode")) prefs.fitMode = j.getInt("fitMode")
        if (j.has("clockEnabled")) prefs.clockEnabled = j.getBoolean("clockEnabled")
        if (j.has("clockPosition")) prefs.clockPosition = j.getInt("clockPosition").coerceIn(0, 3)
        if (j.has("clockSize")) prefs.clockSize = j.getInt("clockSize")
        if (j.has("timeZone")) {
            val name = j.optString("timeZone", "").trim()
            if (name.isNotEmpty() && runCatching { java.time.ZoneId.of(name) }.isFailure) throw HttpError(400, "タイムゾーンの名前が正しくありません")
            prefs.timeZone = name
        }
        if (j.has("timeOffsetSec")) prefs.timeOffsetSec = j.getInt("timeOffsetSec")
        if (j.has("timeFormat")) prefs.timeFormat = j.getInt("timeFormat")
        if (j.has("timeSync")) prefs.timeSync = j.getBoolean("timeSync")
        if (j.has("timeServer")) {
            val host = j.optString("timeServer", "").trim().ifEmpty { "ntp.nict.jp" }
            if (!TimeSync.validHost(host)) throw HttpError(400, "時刻サーバーの名前が正しくありません")
            prefs.timeServer = host
        }
        if (j.has("weatherEnabled")) prefs.weatherEnabled = j.getBoolean("weatherEnabled")
        if (j.has("weatherIntervalMin")) prefs.weatherIntervalMin = j.getInt("weatherIntervalMin")
        if (j.has("weatherSeconds")) prefs.weatherSeconds = j.getInt("weatherSeconds")
        if (j.has("weatherTimeSeries")) prefs.weatherTimeSeries = j.getBoolean("weatherTimeSeries")
        // 天気予報の地域（気象庁のコード。数字のみ）
        fun code(key: String): String? {
            if (j.isNull(key)) return null
            val v = j.get(key).toString()
            if (!Regex("\\d{1,10}").matches(v)) throw HttpError(400, "天気予報の地域が正しくありません")
            return v
        }
        fun name(key: String): String? = if (j.isNull(key)) null else j.getString(key).take(40).ifEmpty { null }
        if (j.has("weatherOffice")) prefs.weatherOffice = code("weatherOffice")
        if (j.has("weatherArea")) prefs.weatherArea = code("weatherArea")
        if (j.has("weatherCity")) prefs.weatherCity = code("weatherCity")
        if (j.has("weatherAreaName")) prefs.weatherAreaName = name("weatherAreaName")
        if (j.has("weatherCityName")) prefs.weatherCityName = name("weatherCityName")
        if (j.has("weatherRegions")) {
            val arr = j.optJSONArray("weatherRegions") ?: throw HttpError(400, "地方の指定が正しくありません")
            val known = Weather.regions(app).map { it.id }
            val picked = (0 until arr.length()).map { arr.optString(it) }
            if (picked.any { it !in known }) throw HttpError(400, "地方の指定が正しくありません")
            prefs.weatherRegions = known.filter { it in picked } // 表の順に並べる
        }
        if (j.has("weatherRegionDay")) prefs.weatherRegionDay = j.getInt("weatherRegionDay")
    }

    private fun json(status: Int, body: JSONObject): Response {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return Response(status, "application/json; charset=utf-8", bytes.size.toLong()) { it.write(bytes) }
    }
}
