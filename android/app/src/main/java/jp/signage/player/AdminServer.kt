package jp.signage.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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

    /** 再生側に知らせるイベント */
    const val EVENT_CONTENT = "content"   // ファイルが変わった → 読み直す
    const val EVENT_SETTINGS = "settings" // 設定が変わった → 画面を作り直す
    const val EVENT_SERVER = "server"     // サーバーの起動・停止

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

    private var failures = 0
    private var lockedUntil = 0L

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
    private fun notify(event: String) = main.post { listeners.forEach { it(event) } }

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
            // 使用中なら次の番号を試す（bind に失敗したソケットは閉じられるので毎回作り直す）
            val bound = (DEFAULT_PORT until DEFAULT_PORT + 10).firstNotNullOfOrNull { p ->
                runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } }.getOrNull()
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
                val client = try { s.accept() } catch (e: IOException) { break }
                pool.execute { handle(client) }
            }
        }
    }

    private fun stop() {
        Peers.stop()
        server?.let { runCatching { it.close() } }
        server = null
        port = 0
        notify(EVENT_SERVER)
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
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

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
        while (true) {
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
            "Access-Control-Allow-Headers" to "X-Pin, Content-Type",
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
        if (req.method == "OPTIONS") {
            val origin = req.headers["origin"]
            if (origin == null || !isLanOrigin(origin)) throw HttpError(403, "許可されていない接続元です")
            return Response(204, "text/plain", 0) {}
        }

        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            val html = app.assets.open("admin.html").use { it.readBytes() }
            return Response(200, "text/html; charset=utf-8", html.size.toLong()) { it.write(html) }
        }
        if (!req.path.startsWith("/api/")) throw HttpError(404, "見つかりません")
        checkPin(req.headers["x-pin"])

        val prefs = Prefs(app)
        return when ("${req.method} ${req.path}") {
            "GET /api/state" -> json(200, state(prefs))
            "PUT /api/upload" -> {
                val folder = writableFolder(prefs, req.query["zone"])
                val name = FolderStore.sanitize(req.query["name"] ?: "")
                    ?: throw HttpError(400, "画像・動画のファイルのみアップロードできます")
                if (req.contentLength <= 0) throw HttpError(400, "ファイルが空です")
                val saved = FolderStore.upload(app, folder, name, req.contentLength, req.body)
                json(200, JSONObject().put("ok", true).put("name", saved))
            }
            "POST /api/delete" -> {
                val body = req.json()
                val folder = writableFolder(prefs, body.optString("zone"))
                FolderStore.delete(app, folder, body.getString("name"), prefs.recursive)
                json(200, JSONObject().put("ok", true))
            }
            "GET /api/file" -> {
                val folder = folderOf(prefs, req.query["zone"])
                val (stream, entry) = FolderStore.open(app, folder, req.query["name"] ?: "", prefs.recursive)
                val type = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                Response(200, type, entry.size) { out -> stream.use { it.copyTo(out, 256 * 1024) } }
            }
            "GET /api/devices" -> {
                val list = JSONArray()
                Peers.list().forEach {
                    list.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("version", it.version))
                }
                json(200, JSONObject().put("id", prefs.deviceId).put("name", prefs.deviceName).put("peers", list))
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

    private fun isLan(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
        is Inet6Address -> a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
            (a.address[0].toInt() and 0xFE) == 0xFC || // ユニークローカル fc00::/7
            (a.isIPv4CompatibleAddress && isLan(InetAddress.getByAddress(a.address.copyOfRange(12, 16))))
        else -> false
    }

    @Synchronized
    private fun checkPin(pin: String?) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) throw HttpError(429, "PIN を続けて間違えたため、しばらく操作できません")
        val expected = Prefs(app).adminPin
        val ok = pin != null && MessageDigest.isEqual(pin.toByteArray(), expected.toByteArray())
        if (ok) {
            failures = 0
            return
        }
        failures++
        if (failures >= 5) {
            failures = 0
            lockedUntil = now + 60_000
        }
        throw HttpError(401, "PIN が違います")
    }

    private fun zoneIndex(prefs: Prefs, raw: String?): Int {
        val i = raw?.toIntOrNull() ?: throw HttpError(400, "区画が指定されていません")
        if (i !in 0 until Prefs.zoneCount(prefs.layout) || prefs.zoneType(i) != Prefs.ZONE_FOLDER) {
            throw HttpError(400, "フォルダの区画ではありません")
        }
        return i
    }

    private fun folderOf(prefs: Prefs, raw: String?): Uri =
        prefs.zoneFolder(zoneIndex(prefs, raw)) ?: throw HttpError(400, "この区画のフォルダが未設定です")

    private fun writableFolder(prefs: Prefs, raw: String?): Uri {
        val folder = folderOf(prefs, raw)
        if (!FolderStore.isWritable(app, folder)) {
            throw HttpError(403, "このフォルダには書き込めません。端末の設定画面でフォルダを選び直すか、「アプリ専用フォルダ」を使ってください")
        }
        return folder
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
            if (type == Prefs.ZONE_FOLDER) {
                val folder = prefs.zoneFolder(i)
                z.put("folder", folder?.let(MediaScanner::describe) ?: "")
                if (folder != null) {
                    z.put("writable", FolderStore.isWritable(app, folder))
                    val files = JSONArray()
                    runCatching { MediaScanner.scan(app.contentResolver, folder, prefs.recursive) }
                        .onFailure { z.put("error", "フォルダを読み込めません") }
                        .getOrDefault(emptyList())
                        .forEach {
                            files.put(JSONObject().put("name", it.name).put("video", it.isVideo).put("size", it.size))
                        }
                    z.put("files", files)
                }
            }
            zones.put(z)
        }
        val settings = JSONObject()
            .put("layout", prefs.layout)
            .put("splitPercent", prefs.splitPercent)
            .put("mainPercent", prefs.mainPercent)
            .put("sidePercent", prefs.sidePercent)
            .put("zoneTypes", JSONArray((0 until Prefs.MAX_ZONES).map(prefs::zoneType)))
            .put("imageSeconds", prefs.imageSeconds)
            .put("shuffle", prefs.shuffle)
            .put("recursive", prefs.recursive)
            .put("videoSound", prefs.videoSound)
            .put("videoCompat", prefs.videoCompat)
            .put("fitMode", prefs.fitMode)
            .put("clockEnabled", prefs.clockEnabled)
            .put("clockPosition", prefs.clockPosition)
            .put("clockSize", prefs.clockSize)
            .put("weatherEnabled", prefs.weatherEnabled)
            .put("weatherIntervalMin", prefs.weatherIntervalMin)
            .put("weatherSeconds", prefs.weatherSeconds)
            .put("weatherTimeSeries", prefs.weatherTimeSeries)
            .put("weatherPlace", prefs.weatherCityName ?: prefs.weatherAreaName ?: "")
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val ticker = JSONObject()
            .put("standing", Ticker.standing(app)?.toJson() ?: JSONObject.NULL)
            .put("schedules", JSONArray(Ticker.schedules(app).map { it.toJson() }))
        return JSONObject()
            .put("ticker", ticker)
            .put("id", prefs.deviceId)
            .put("name", prefs.deviceName)
            .put("device", android.os.Build.MODEL)
            .put("version", version ?: "")
            .put("zones", zones)
            .put("settings", settings)
    }

    private fun applySettings(prefs: Prefs, j: JSONObject) {
        if (j.has("layout")) prefs.layout = j.getInt("layout").coerceIn(0, 3)
        if (j.has("splitPercent")) prefs.splitPercent = j.getInt("splitPercent")
        if (j.has("mainPercent")) prefs.mainPercent = j.getInt("mainPercent")
        if (j.has("sidePercent")) prefs.sidePercent = j.getInt("sidePercent")
        j.optJSONArray("zoneTypes")?.let { a ->
            for (i in 0 until minOf(a.length(), Prefs.MAX_ZONES)) {
                val t = a.getInt(i)
                if (t == Prefs.ZONE_FOLDER || t == Prefs.ZONE_WEATHER) prefs.setZoneType(i, t)
            }
        }
        if (j.has("imageSeconds")) prefs.imageSeconds = j.getInt("imageSeconds")
        if (j.has("shuffle")) prefs.shuffle = j.getBoolean("shuffle")
        if (j.has("recursive")) prefs.recursive = j.getBoolean("recursive")
        if (j.has("videoSound")) prefs.videoSound = j.getBoolean("videoSound")
        if (j.has("videoCompat")) prefs.videoCompat = j.getBoolean("videoCompat")
        if (j.has("fitMode")) prefs.fitMode = j.getInt("fitMode")
        if (j.has("clockEnabled")) prefs.clockEnabled = j.getBoolean("clockEnabled")
        if (j.has("clockPosition")) prefs.clockPosition = j.getInt("clockPosition").coerceIn(0, 3)
        if (j.has("clockSize")) prefs.clockSize = j.getInt("clockSize")
        if (j.has("weatherEnabled")) prefs.weatherEnabled = j.getBoolean("weatherEnabled")
        if (j.has("weatherIntervalMin")) prefs.weatherIntervalMin = j.getInt("weatherIntervalMin")
        if (j.has("weatherSeconds")) prefs.weatherSeconds = j.getInt("weatherSeconds")
        if (j.has("weatherTimeSeries")) prefs.weatherTimeSeries = j.getBoolean("weatherTimeSeries")
    }

    private fun json(status: Int, body: JSONObject): Response {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return Response(status, "application/json; charset=utf-8", bytes.size.toLong()) { it.write(bytes) }
    }
}
