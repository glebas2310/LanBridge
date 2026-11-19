package com.lanbridge.app.network

import android.annotation.SuppressLint
import android.util.Log
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object TlsCertificatePinner {

    private const val TAG = "TlsCertificatePinner"

    fun normalizeFingerprint(fp: String): String {
        val clean = fp.trim().uppercase()
        return if (clean.startsWith("SHA256:")) {
            clean
        } else if (clean.startsWith("SHA-256:")) {
            "SHA256:" + clean.removePrefix("SHA-256:")
        } else {
            "SHA256:$clean"
        }
    }

    fun calculateFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val der = cert.encoded
        val hash = digest.digest(der)
        return "SHA256:" + hash.joinToString("") { "%02X".format(it) }
    }

    fun createTrustManager(pinnedFingerprint: String): X509TrustManager {
        val expected = normalizeFingerprint(pinnedFingerprint)

        return object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Client trust check not needed for outbound client connections
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty or null")
                }

                val leafCert = chain[0]
                val actualFp = calculateFingerprint(leafCert)

                Log.d(TAG, "Validating server cert. Actual: $actualFp, Expected: $expected")

                if (!actualFp.equals(expected, ignoreCase = true)) {
                    val errorMsg = "TLS Certificate Pinning failure! Expected: $expected, Actual: $actualFp"
                    Log.e(TAG, errorMsg)
                    throw CertificateException(errorMsg)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return emptyArray()
            }
        }
    }

    fun createSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        return sslContext.socketFactory
    }

    fun createHostnameVerifier(): HostnameVerifier {
        return HostnameVerifier { _, _ ->
            // Hostname verification bypassed because trust is bound directly to the pinned X.509 certificate SHA-256 fingerprint
            true
        }
    }

    fun createPinnedOkHttpClient(
        pinnedFingerprint: String,
        timeoutSeconds: Long = 10L
    ): OkHttpClient {
        val trustManager = createTrustManager(pinnedFingerprint)
        val sslSocketFactory = createSslSocketFactory(trustManager)
        val hostnameVerifier = createHostnameVerifier()

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
