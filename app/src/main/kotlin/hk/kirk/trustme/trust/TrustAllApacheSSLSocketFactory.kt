package hk.kirk.trustme.trust

import org.apache.http.conn.ssl.SSLSocketFactory
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * 信任一切的 Apache SSLSocketFactory
 * 从 ApacheHttpHook 中抽出为共享类，供 hook 与 UI 复用
 */
class TrustAllApacheSSLSocketFactory(truststore: KeyStore) : SSLSocketFactory(truststore) {

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(TrustAllManager.getInstance()), null)
    }

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        return sslContext.socketFactory.createSocket(socket, host, port, autoClose)
    }

    override fun createSocket(): Socket {
        return sslContext.socketFactory.createSocket()
    }

    companion object {
        /** getSocketFactory() Hook 使用的全局实例 */
        @JvmStatic
        fun getDefault(): SSLSocketFactory = try {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
            trustStore.load(null, null)
            TrustAllApacheSSLSocketFactory(trustStore)
        } catch (_: Exception) {
            SSLSocketFactory.getSocketFactory()
        }
    }
}
