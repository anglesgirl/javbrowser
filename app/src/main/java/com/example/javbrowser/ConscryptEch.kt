package com.example.javbrowser

import android.util.Log
import org.conscrypt.Conscrypt
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Conscrypt ECH wiring for OkHttp, adapted from the proven web-to-app
 * ConscryptEchForwarder (same provider install, same PolicyTrustManager
 * pattern, same socket-factory hook).
 *
 * How it works:
 *  - Conscrypt is installed as security provider #1, so the Conscrypt
 *    SSLContext is what OkHttp gets.
 *  - Conscrypt reads the [NetworkSecurityPolicy] reflectively, through
 *    [PolicyTrustManager.getNetworkSecurityPolicy], once per host during
 *    the handshake. The policy answers REQUIRED when [EchDoh] has an
 *    ECHConfigList for the host, DISABLED otherwise.
 *  - [EchSocketFactory] is the only createSocket overload OkHttp uses for
 *    HTTPS (Socket, host, port, autoClose). Before OkHttp starts the
 *    handshake it calls [Conscrypt.setEchConfigList] and
 *    [Conscrypt.setHostname] on the fresh socket.
 *
 * PROGUARD WARNING: [PolicyTrustManager.getNetworkSecurityPolicy] is
 * reached by Conscrypt via reflection. If R8 strips or renames it,
 * setEchConfigList becomes a silent no-op (no error, no ECH extension on
 * the wire). proguard-rules.pro keeps org.conscrypt.** and this whole
 * object for exactly that reason. The upstream release build does not
 * minify today, but the keeps are mandatory the day it does.
 */
object ConscryptEch {

    private const val TAG = "ConscryptEch"

    private val providerLock = Any()

    @Volatile
    private var providerInstalled: Boolean? = null

    /**
     * Installs Conscrypt as provider #1 if it is not already present.
     * Idempotent; returns true when the Conscrypt provider is available
     * afterwards. Callers that cannot run ECH (provider missing) must fall
     * back to the Cronet path, never to a plaintext OkHttp path for an
     * ECH-configured host.
     */
    fun ensureProvider(): Boolean {
        providerInstalled?.let { return it }
        synchronized(providerLock) {
            providerInstalled?.let { return it }
            val ok = try {
                if (Security.getProvider("Conscrypt") == null) {
                    Security.insertProviderAt(Conscrypt.newProvider(), 1)
                }
                Security.getProvider("Conscrypt") != null
            } catch (e: Exception) {
                Log.w(TAG, "Conscrypt provider install failed: ${e.message}")
                false
            }
            providerInstalled = ok
            return ok
        }
    }

    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Delegating trust manager whose [getNetworkSecurityPolicy] Conscrypt
     * reflects on during the handshake. Do NOT rename this method or the
     * class: Conscrypt finds it by name, and R8 will happily strip it.
     *
     * The 3-arg [checkServerTrusted] below is equally reflection-only and
     * equally mandatory: for a host with domain encryption enabled,
     * Conscrypt's handshake reflectively looks up
     * checkServerTrusted(X509Certificate[], String, String) and, not
     * finding it, fails the handshake with "Domain specific
     * configurations require that hostname aware checkServerTrusted...
     * is used" — before any network-dependent step. That is exactly how
     * every ECH host failed on 2026-10-30 (co3 hit the same failure on
     * 2026-09-27 and fixed it with this same method). The 2-arg
     * implementation alone is not enough: the wrapper hides the
     * delegate's hostname-aware overloads from Conscrypt's reflective
     * dispatch, so the wrapper must re-expose one.
     */
    class PolicyTrustManager(
        private val delegate: X509TrustManager,
        private val configForHost: (String) -> ByteArray?,
    ) : X509TrustManager {

        private val policy = object : NetworkSecurityPolicy {
            override fun isCertificateTransparencyVerificationRequired(hostname: String): Boolean = false

            override fun getCertificateTransparencyVerificationReason(
                hostname: String,
            ): CertificateTransparencyVerificationReason =
                CertificateTransparencyVerificationReason.UNKNOWN

            override fun getDomainEncryptionMode(hostname: String): DomainEncryptionMode =
                if (configForHost(hostname) != null) {
                    DomainEncryptionMode.REQUIRED
                } else {
                    DomainEncryptionMode.DISABLED
                }
        }

        fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = policy

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
        }

        /**
         * Conscrypt ECH handshake's reflective hostname-aware check
         * (ported from co3's proven PolicyTrustManager). Only called via
         * reflection, hence @Suppress("unused") — and covered by the
         * ConscryptEch** keep in proguard-rules.pro, so R8 cannot strip
         * or rename it.
         */
        @Suppress("unused")
        fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            hostname: String?,
        ) {
            if (hostname == null) {
                delegate.checkServerTrusted(chain, authType)
                return
            }
            try {
                // System TrustManagerImpl's 3-arg overload (hidden API, reflective call).
                val m = delegate.javaClass.getMethod(
                    "checkServerTrusted",
                    Array<X509Certificate>::class.java,
                    String::class.java,
                    String::class.java,
                )
                m.invoke(delegate, chain, authType, hostname)
            } catch (_: NoSuchMethodException) {
                // Delegate has no hostname-aware overload: fall back to 2-arg (chain check only).
                delegate.checkServerTrusted(chain, authType)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw (e.cause ?: e)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    /**
     * Wraps the Conscrypt socket factory. Only the (Socket, host, port,
     * autoClose) overload injects ECH — it is the one OkHttp uses for
     * HTTPS; the other overloads have no reliable hostname for the
     * ECHConfigList lookup and delegate plain.
     */
    class EchSocketFactory(
        private val delegate: SSLSocketFactory,
        private val configForHost: (String) -> ByteArray?,
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            val socket = delegate.createSocket(s, host, port, autoClose)
            return maybeEch(socket, host)
        }

        override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort)

        override fun createSocket(): Socket = delegate.createSocket()

        private fun maybeEch(socket: Socket, host: String): Socket {
            if (socket is SSLSocket) {
                try {
                    Conscrypt.setHostname(socket, host)
                    val config = configForHost(host)
                    if (config != null) {
                        Conscrypt.setEchConfigList(socket, config)
                        Log.d(TAG, "ECH configured for $host (${config.size}B)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ECH setup failed for $host: ${e.message}")
                }
            }
            return socket
        }
    }

    /**
     * The (socket factory, trust manager) pair OkHttp's
     * sslSocketFactory(factory, trustManager) needs. By default the
     * policy and the socket factory both resolve the ECH config
     * through [EchDoh], so a host that published a config gets
     * REQUIRED + the config bytes, and a host that did not gets
     * DISABLED + plain TLS 1.3. Callers may inject a fixed config
     * provider instead (the shared-ECH rescue client injects the
     * official shared config for every host).
     *
     * "TLS" (not TLSv1.3-only) on purpose: hosts without an ECH config
     * must still connect over TLS 1.2 when that is all they offer. ECH
     * itself requires TLS 1.3, and a REQUIRED policy fails closed when a
     * configured host cannot negotiate it.
     */
    fun newSslPair(configForHost: (String) -> ByteArray? = { host -> EchDoh.configFor(host) }): Pair<SSLSocketFactory, X509TrustManager> {
        if (!ensureProvider()) throw IllegalStateException("Conscrypt provider unavailable")
        val trustManager = PolicyTrustManager(systemTrustManager(), configForHost)
        val context = SSLContext.getInstance("TLS", "Conscrypt")
        context.init(null, arrayOf(trustManager), null)
        return EchSocketFactory(context.socketFactory, configForHost) to trustManager
    }
}
