package jp.signage.player

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * 管理画面の通信を HTTPS にするための、自己署名の証明書。
 * 鍵は Android のキーストアの中で作り、アプリの外には出ない。ブラウザには初回に警告が出るので、
 * 画面に出す SHA-256 フィンガープリントと見比べて確かめてから許可する。
 */
object TlsSupport {
    private const val ALIAS = "signage-tls"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(ks: KeyStore) {
        if (ks.containsAlias(ALIAS)) {
            // 期限が近ければ作り直す
            val cert = ks.getCertificate(ALIAS) as? java.security.cert.X509Certificate
            if (cert != null && cert.notAfter.time > System.currentTimeMillis() + 30L * 24 * 3600_000) return
            ks.deleteEntry(ALIAS)
        }
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=Signage"))
            .setCertificateNotBefore(Date(now - 24 * 3600_000L))
            .setCertificateNotAfter(Date(now + 3650L * 24 * 3600_000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    /** サーバー用の TLS の設定。用意できなければ例外 */
    fun serverContext(): SSLContext {
        val ks = keyStore()
        ensureKey(ks)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** 証明書の SHA-256 フィンガープリント（AA:BB:… の形）。無ければ空 */
    fun fingerprint(): String = runCatching {
        val cert = keyStore().getCertificate(ALIAS) ?: return@runCatching ""
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")
}
