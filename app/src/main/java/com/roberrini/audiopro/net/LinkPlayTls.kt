package com.roberrini.audiopro.net

import android.content.Context
import android.util.Base64
import com.roberrini.audiopro.R
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS setup for LinkPlay speakers.
 *
 * Newer Audio Pro firmware (MkII / W generation) answers HTTPS on port 4443 and
 * asks the client for a certificate (mutual TLS). LinkPlay ships one shared client
 * certificate inside its apps; open-source projects such as linkplay-cli and pywiim
 * publish the same one. It is bundled in res/raw/linkplay_client.pem.
 *
 * The speaker itself presents a self-signed certificate, so server verification is
 * disabled. That is acceptable here because the app only ever talks to a device on
 * the local network that the user picked.
 */
object LinkPlayTls {

    @Volatile private var cached: SSLSocketFactory? = null

    val trustAllHostnames = HostnameVerifier { _, _ -> true }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun socketFactory(context: Context): SSLSocketFactory {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val pem = context.resources.openRawResource(R.raw.linkplay_client)
                .bufferedReader().use { it.readText() }
            val factory = build(pem)
            cached = factory
            return factory
        }
    }

    private fun build(pem: String): SSLSocketFactory {
        val keyB64 = section(pem, "PRIVATE KEY")
        val certB64 = section(pem, "CERTIFICATE")

        val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyB64, Base64.DEFAULT))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.decode(certB64, Base64.DEFAULT).inputStream())

        val password = CharArray(0)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("linkplay", privateKey, password, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        return ctx.socketFactory
    }

    private fun section(pem: String, label: String): String {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val start = pem.indexOf(begin)
        val stop = pem.indexOf(end, start)
        require(start >= 0 && stop > start) { "Missing $label in client certificate file" }
        return pem.substring(start + begin.length, stop).replace("\\s".toRegex(), "")
    }
}
