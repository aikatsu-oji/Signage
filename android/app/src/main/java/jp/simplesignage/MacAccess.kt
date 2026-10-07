package jp.simplesignage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 操作できる端末の制限（MAC アドレス）。Windows 版の devices.py と同じ考え方。
 * 接続元の MAC アドレスは ARP テーブル（/proc/net/arp）から調べる。
 * Android 10 以降は、アプリからこのテーブルを読めない機種が多く、その場合は制限を使えない（締め出しを防ぐため）。
 */
object MacAccess {
    const val MAX_DEVICES = 50

    data class Device(val mac: String, val name: String)

    fun normalize(text: String?): String? {
        val h = (text ?: "").lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (h.length != 12 || h == "0".repeat(12) || h == "f".repeat(12)) return null
        return h.chunked(2).joinToString(":")
    }

    /** 検査して整える。不正な MAC があれば IllegalArgumentException */
    fun clean(items: JSONArray): List<Device> {
        val seen = HashSet<String>()
        val result = ArrayList<Device>()
        for (i in 0 until items.length()) {
            val raw = items.get(i)
            val obj = raw as? JSONObject
            val text = obj?.optString("mac") ?: raw.toString()
            val mac = normalize(text) ?: throw IllegalArgumentException("MAC アドレスが正しくありません: $text")
            if (!seen.add(mac)) continue
            result.add(Device(mac, (obj?.optString("name") ?: "").trim().take(30)))
        }
        if (result.size > MAX_DEVICES) throw IllegalArgumentException("登録できるのは $MAX_DEVICES 台までです")
        return result
    }

    fun toJson(list: List<Device>): JSONArray =
        JSONArray().apply { list.forEach { put(JSONObject().put("mac", it.mac).put("name", it.name)) } }

    fun fromJson(text: String?): List<Device> =
        runCatching { clean(JSONArray(text ?: "[]")) }.getOrDefault(emptyList())

    private fun v4(a: InetAddress): Inet4Address? = when (a) {
        is Inet4Address -> a
        is Inet6Address -> if (a.isIPv4CompatibleAddress || isMapped(a)) {
            InetAddress.getByAddress(a.address.copyOfRange(12, 16)) as? Inet4Address
        } else null
        else -> null
    }

    private fun isMapped(a: Inet6Address): Boolean {
        val b = a.address
        return (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
    }

    fun isLoopback(a: InetAddress): Boolean = a.isLoopbackAddress || v4(a)?.isLoopbackAddress == true

    /** /proc/net/arp の内容から {IPv4: MAC} を作る */
    fun parseArp(text: String): Map<String, String> {
        val table = HashMap<String, String>()
        val re = Regex("""(\d{1,3}(?:\.\d{1,3}){3})\s+(?:0x\d+\s+0x\d+\s+)?([0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})""")
        for (line in text.lines()) {
            val m = re.find(line) ?: continue
            normalize(m.groupValues[2])?.let { table[m.groupValues[1]] = it }
        }
        return table
    }

    private const val ARP_FILE = "/proc/net/arp"

    /** この端末が、接続元の MAC アドレスを調べられるか */
    fun canResolve(): Boolean = runCatching { File(ARP_FILE).readText(); true }.getOrDefault(false)

    fun lookup(a: InetAddress): String? {
        val ip = v4(a) ?: return null
        if (ip.isLoopbackAddress) return null
        return runCatching { parseArp(File(ARP_FILE).readText())[ip.hostAddress] }.getOrNull()
    }

    /** 許可なら ""、だめなら理由。制限が OFF・登録が 0 台なら誰でも許可。この端末自身は常に許可 */
    fun gate(lock: Boolean, devices: List<Device>, from: InetAddress, mac: String?): String {
        if (!lock || devices.isEmpty() || isLoopback(from)) return ""
        if (mac == null) return "この端末の MAC アドレスを確認できないため、操作できません"
        if (devices.any { it.mac == mac }) return ""
        return "この端末（MAC アドレス $mac）は、操作が許可されていません"
    }

    /** 制限を ON にする・一覧を変えるとき、操作中の端末自身が締め出されないか。問題なければ "" */
    fun checkUpdate(lock: Boolean, devices: List<Device>, from: InetAddress, mac: String?): String {
        if (!lock) return ""
        if (devices.isEmpty()) return "制限を ON にするには、操作を許可する端末を 1 台以上登録してください"
        return gate(true, devices, from, mac).replace(
            "は、操作が許可されていません",
            "が登録されていません。このままだと、いま操作しているこの端末が操作できなくなります",
        )
    }
}
