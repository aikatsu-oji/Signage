package jp.signage.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * 同じネットワーク内のサイネージ端末を見つける（mDNS / DNS-SD）。
 * 自分を「_signage._tcp」として登録し、ほかの端末を探して一覧にする。管理画面の端末一覧に使う。
 */
object Peers {
    private const val SERVICE_TYPE = "_signage._tcp."

    data class Peer(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val version: String,
        val lastSeen: Long,
        val https: Boolean = false,
    ) {
        val url get() = "${if (https) "https" else "http"}://$host:$port"
    }

    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())

    private val peers = ConcurrentHashMap<String, Peer>()
    /** mDNS のサービス名 → 端末 ID（見えなくなったときに消すため） */
    private val serviceIds = ConcurrentHashMap<String, String>()
    private var selfId = ""

    // 古い Android では解決を同時に1件しかできないので順番に行う
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun list(): List<Peer> = peers.values.sortedBy { it.name }

    @Synchronized
    fun start(context: Context, port: Int) {
        stop()
        val app = context.applicationContext
        val prefs = Prefs(app)
        selfId = prefs.deviceId
        val manager = app.getSystemService(NsdManager::class.java) ?: return
        nsd = manager

        val info = NsdServiceInfo().apply {
            serviceName = prefs.deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", prefs.deviceId)
            setAttribute("name", prefs.deviceName)
            setAttribute("https", if (AdminServer.tlsActive) "1" else "0")
            setAttribute("ver", runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull() ?: "")
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }.also { runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                serviceIds.remove(info.serviceName)?.let { peers.remove(it) }
            }
        }.also { runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    @Synchronized
    fun stop() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        nsd = null
        peers.clear()
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

    private fun record(info: NsdServiceInfo) {
        val attrs = info.attributes
        fun attr(key: String) = attrs[key]?.let { String(it, Charsets.UTF_8) } ?: ""
        val id = attr("id").ifEmpty { return }
        if (id == selfId) return
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
            https = attr("https") == "1",
        )
    }
}
