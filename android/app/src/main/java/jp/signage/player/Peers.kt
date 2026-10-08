package jp.signage.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 同じネットワーク内のサイネージ端末を見つける（mDNS / DNS-SD）。
 * 自分を「_signage._tcp」として登録し、ほかの端末を探して一覧にする。管理画面の端末一覧に使う。
 */
object Peers {
    private const val SERVICE_TYPE = "_signage._tcp."
    /** mDNS が使えない機種（Fire TV など）でも見つけられるよう、UDP のブロードキャストでも知らせ合う */
    const val BEACON_PORT = 48080
    private const val BEACON_INTERVAL_MS = 5_000L
    private const val BEACON_EXPIRE_MS = 20_000L
    private const val MAX_PEERS = 500
    /** 「一覧に出さない端末」の記録の上限（偽の知らせで増やされないように）と、UDP 由来の記録の有効期間 */
    private const val MAX_HIDDEN = 200
    private const val HIDDEN_TTL_MS = 60_000L

    data class Peer(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val version: String,
        val lastSeen: Long,
        /** UDP の知らせ（ビーコン）で見つけた端末。しばらく聞こえなくなったら一覧から消す */
        val beacon: Boolean = false,
    ) {
        val url get() = "http://$host:$port"
    }

    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())

    private val peers = ConcurrentHashMap<String, Peer>()

    /** 見つかったが、グループが違うため、一覧に出さない端末（id → 名前と理由）。なぜ見えないかを、管理画面で案内するため */
    class Hidden(val name: String, val reason: String, val beacon: Boolean = false, val at: Long = System.currentTimeMillis())
    private val hidden = ConcurrentHashMap<String, Hidden>()
    fun listHidden(): List<Hidden> { pruneHidden(); return hidden.values.sortedBy { it.name } }

    /** UDP の知らせから作った記録は、一定時間で消す */
    private fun pruneHidden() {
        val now = System.currentTimeMillis()
        hidden.entries.removeIf { it.value.beacon && now - it.value.at > HIDDEN_TTL_MS }
    }

    /** 「一覧に出さない端末」を記録する。件数に上限があり、いっぱいのときは新しい id を記録しない */
    private fun hide(id: String, name: String, reason: String, beacon: Boolean) {
        pruneHidden()
        if (!hidden.containsKey(id) && hidden.size >= MAX_HIDDEN) return
        hidden[id] = Hidden(name, reason, beacon)
    }
    /** mDNS のサービス名 → 端末 ID（見えなくなったときに消すため） */
    private val serviceIds = ConcurrentHashMap<String, String>()
    private var selfId = ""
    /** この端末のグループの識別子（空ならグループなし）。同じ識別子の端末だけを一覧に出す */
    private var selfGroup = ""
    private var selfCode = ""

    // 古い Android では解決を同時に1件しかできないので順番に行う
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun list(): List<Peer> {
        val now = System.currentTimeMillis()
        return peers.values.filter { !it.beacon || now - it.lastSeen < BEACON_EXPIRE_MS }.sortedBy { it.name }
    }

    @Volatile private var beaconSocket: DatagramSocket? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    @Synchronized
    fun start(context: Context, port: Int) {
        stop()
        val app = context.applicationContext
        val prefs = Prefs(app)
        selfId = prefs.deviceId
        selfGroup = GroupCode.ident(prefs.groupCode)
        selfCode = prefs.groupCode
        startBeacon(app, port, prefs)
        val manager = app.getSystemService(NsdManager::class.java) ?: return
        nsd = manager

        val info = NsdServiceInfo().apply {
            serviceName = prefs.deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", prefs.deviceId)
            setAttribute("name", prefs.deviceName)
            setAttribute("grp", GroupCode.ident(prefs.groupCode))
            setAttribute("ver", runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull() ?: "")
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // 起動直後などで失敗することがあるので、少し待ってやり直す（UDP の知らせは、これと別に動く）
                main.postDelayed({ if (nsd === manager && registration === this) runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, this) } }, 5_000)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }.also { runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.postDelayed({ if (nsd === manager && discovery === this) runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this) } }, 5_000)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                serviceIds.remove(info.serviceName)?.let { peers.remove(it); hidden.remove(it) }
            }
        }.also { runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    @Synchronized
    fun stop() {
        stopBeacon()
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        nsd = null
        peers.clear()
        hidden.clear()
        serviceIds.clear()
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val manager = nsd
        val next = synchronized(resolveQueue) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        if (manager == null) return
        @Suppress("DEPRECATION")
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.postDelayed({ resolveNext() }, 200)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    record(info)
                    main.post { resolveNext() }
                }
            })
        }.onFailure { main.post { resolveNext() } }
    }

    // ---- UDP ブロードキャスト（mDNS の代わり） ----

    private fun startBeacon(app: Context, port: Int, prefs: Prefs) {
        stopBeacon()
        runCatching {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifi?.createMulticastLock("signage-beacon")?.apply { setReferenceCounted(false); acquire() }
        }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""
        val sock = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(java.net.InetSocketAddress(BEACON_PORT)) }
        }.getOrNull() ?: return
        beaconSocket = sock
        // 受信
        Thread({
            val buf = ByteArray(1024)
            while (beaconSocket === sock && !sock.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val from = (pkt.address as? Inet4Address)?.hostAddress ?: continue
                    recordBeacon(String(pkt.data, 0, pkt.length, Charsets.UTF_8), from)
                } catch (e: Throwable) {
                    if (sock.isClosed) break
                }
            }
        }, "peer-beacon-rx").apply { isDaemon = true }.start()
        // 送信
        Thread({
            while (beaconSocket === sock && !sock.isClosed) {
                runCatching {
                    val p = Prefs(app)
                    val code = p.groupCode
                    fun message(ip: String?): ByteArray {
                        val j = JSONObject().put("app", "signage").put("id", p.deviceId).put("name", p.deviceName)
                            .put("port", port).put("ver", version).put("grp", GroupCode.ident(code))
                        // グループがあるときは、送り元の IP とコードで作った署名を付ける（受け取る側が、なりすましを見抜く）
                        if (code.isNotEmpty() && ip != null) j.put("ip", ip).put("sig", GroupCode.sign(code, p.deviceId, port, ip))
                        return j.toString().toByteArray(Charsets.UTF_8)
                    }
                    // ネットワークごとに、そのネットワークでの自分の IP を載せて送る
                    var sent = false
                    for ((ip, bcast) in interfaceBroadcasts()) {
                        val msg = message(ip)
                        runCatching { sock.send(DatagramPacket(msg, msg.size, bcast, BEACON_PORT)); sent = true }
                    }
                    if (!sent && code.isEmpty()) {
                        val msg = message(null)
                        runCatching { sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), BEACON_PORT)) }
                    }
                }
                try { Thread.sleep(BEACON_INTERVAL_MS) } catch (e: InterruptedException) { break }
            }
        }, "peer-beacon-tx").apply { isDaemon = true }.start()
    }

    private fun stopBeacon() {
        beaconSocket?.let { runCatching { it.close() } }
        beaconSocket = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    /** 使っているネットワークごとの（この端末の IPv4 アドレス, ブロードキャストアドレス） */
    private fun interfaceBroadcasts(): List<Pair<String, InetAddress>> {
        val list = mutableListOf<Pair<String, InetAddress>>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    val ip = (ia.address as? Inet4Address)?.hostAddress ?: continue
                    list.add(ip to b)
                }
            }
        }
        return list
    }

    private fun recordBeacon(text: String, fromIp: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (j.optString("app") != "signage") return
        val id = j.optString("id").ifEmpty { return }
        if (id == selfId) return
        val port = j.optInt("port", 0)
        if (port !in 1..65535) return
        val name = j.optString("name").take(60).ifEmpty { fromIp }
        val grp = j.optString("grp")
        if (grp != selfGroup) {
            val reason = if (selfGroup.isEmpty()) "グループを設定している端末（この端末は未設定）"
            else if (grp.isEmpty()) "グループ未設定、または、古い版" else "別のグループ"
            // 署名の無い知らせで、本物の端末を一覧から消せないよう、ここでは peers を触らない
            // （グループを変えた端末は、知らせが途絶えて、20 秒ほどで一覧から外れる）
            if (!peers.containsKey(id)) hide(id, name, reason, beacon = true)
            return
        }
        // グループがあるときは、同じコードで署名された知らせだけを信じる。
        // 署名の無い・合わない知らせを信じると、偽の端末に PIN とグループコードを送ってしまう
        if (selfGroup.isNotEmpty()) {
            val code = selfCode
            val signedIp = j.optString("ip")
            val ok = code.isNotEmpty() && signedIp == fromIp &&
                java.security.MessageDigest.isEqual(j.optString("sig").toByteArray(), GroupCode.sign(code, id, port, signedIp).toByteArray())
            if (!ok) {
                if (!peers.containsKey(id)) hide(id, name, "古い版、または、署名が合わない知らせ", beacon = true)
                return
            }
        }
        if (peers.size >= MAX_PEERS && !peers.containsKey(id)) return
        hidden.remove(id)
        peers[id] = Peer(id, name, fromIp, port, j.optString("ver").take(20), System.currentTimeMillis(), beacon = true)
    }

    private fun record(info: NsdServiceInfo) {
        val attrs = info.attributes
        fun attr(key: String) = attrs[key]?.let { String(it, Charsets.UTF_8) } ?: ""
        val id = attr("id").ifEmpty { return }
        if (id == selfId) return
        // 別のグループ（組織）の端末は、一覧に出さない
        if (attr("grp") != selfGroup) {
            val reason = if (selfGroup.isEmpty()) "グループを設定している端末（この端末は未設定）"
            else if (attr("grp").isEmpty()) "グループ未設定、または、古い版" else "別のグループ"
            serviceIds[info.serviceName] = id
            hide(id, attr("name").ifEmpty { info.serviceName }, reason, beacon = false)
            return
        }
        // mDNS の登録は署名できず、同じ LAN の誰でも「同じグループ」を名乗れる。
        // グループがあるときは、署名つきの UDP の知らせだけで端末を見つける
        if (selfGroup.isNotEmpty()) return
        @Suppress("DEPRECATION")
        val host = info.host
        // 管理画面の URL に使うので IPv4 のみ（LAN のサイネージ端末は通常 IPv4 を持つ）
        val address = (host as? Inet4Address)?.hostAddress ?: return
        serviceIds[info.serviceName] = id
        peers[id] = Peer(
            id = id,
            name = attr("name").ifEmpty { info.serviceName },
            host = address,
            port = info.port,
            version = attr("ver"),
            lastSeen = System.currentTimeMillis(),
        )
    }
}
