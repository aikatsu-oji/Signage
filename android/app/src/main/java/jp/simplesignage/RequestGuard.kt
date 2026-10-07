package jp.simplesignage

/**
 * 管理画面のサーバーに届いたリクエストの宛先（Host）を調べる。
 * IP アドレス・localhost・*.local 以外（攻撃者のドメイン名が端末の IP に向けられる「DNS リバインディング」）は拒否する。
 */
object RequestGuard {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val IPV6 = Regex("^[0-9a-f:.]+(%[0-9a-z._-]+)?$")

    fun hostName(hostHeader: String?): String {
        val h = (hostHeader ?: "").trim().lowercase()
        return when {
            h.startsWith("[") -> h.removePrefix("[").substringBefore(']')            // [::1]:8080
            h.count { it == ':' } == 1 -> h.substringBeforeLast(':')                 // 192.168.1.5:8080
            else -> h
        }
    }

    fun hostOk(hostHeader: String?): Boolean {
        val name = hostName(hostHeader)
        if (name.isEmpty()) return false
        if (name == "localhost" || name.endsWith(".local")) return true
        return IPV4.matches(name) || (name.contains(':') && IPV6.matches(name))
    }
}
