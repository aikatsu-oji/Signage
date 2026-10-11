package jp.signage.player

import java.security.MessageDigest

/**
 * グループ（組織）コード。同じコードを持つ端末・管理画面だけが、サイネージを操作できる（Windows 版の group.py と同じ）。
 * 英数字と - _ の 12〜32 文字。空ならグループなし（PIN だけで操作できる）。
 */
object GroupCode {
    private val VALID = Regex("^[A-Za-z0-9_-]{12,32}$")

    fun valid(code: String) = VALID.matches(code)

    /** mDNS に載せる識別子（コードのハッシュの一部）。コード自体は載せない。グループなしは空 */
    fun ident(code: String): String {
        if (code.isEmpty()) return ""
        return MessageDigest.getInstance("SHA-256").digest("signage-group:$code".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * UDP の知らせ（ビーコン）の署名。グループのコードを知らない者は、本物の端末を装った知らせを作れない。
     * 送り元の IP も署名に含めるので、盗み見た知らせを別の端末から送り直しても通らない。
     */
    fun sign(code: String, id: String, port: Int, ip: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return mac.doFinal("signage-beacon|$id|$port|$ip".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
