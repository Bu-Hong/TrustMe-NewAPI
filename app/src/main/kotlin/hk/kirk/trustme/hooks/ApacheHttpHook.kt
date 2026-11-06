package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.trust.TrustAllManager
import hk.kirk.trustme.trust.TrustAllApacheSSLSocketFactory
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import org.apache.http.conn.ClientConnectionManager
import org.apache.http.conn.scheme.PlainSocketFactory
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.scheme.SchemeRegistry
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.impl.conn.SingleClientConnManager
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager
import java.lang.reflect.Field
import java.security.SecureRandom
import java.security.KeyStore
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext

/**
 * Apache HTTP Client Hook
 *
 * Modern API：hook(Executable).intercept(Chain)
 * 旧版 XposedHelpers.setObjectField / callStaticMethod / newInstance 改为标准反射 +
 * Invoker（getInvoker 绕过访问检查，替代旧版 XposedBridge.invokeOriginal 语义）
 */
object ApacheHttpHook {

    fun hook(classLoader: ClassLoader) {
        if (!hasApacheHttpClient()) {
            Logger.d("Apache HTTP Client 未发现，跳过")
            return
        }

        hookDefaultHttpClient(classLoader)
        hookSSLSocketFactory(classLoader)
        hookSchemeRegistry(classLoader)
    }

    private fun setObjectField(target: Any, name: String, value: Any?) {
        val f: Field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(target, value)
    }

    private fun hookDefaultHttpClient(classLoader: ClassLoader) {
        val cls = try {
            classLoader.loadClass("org.apache.http.impl.client.DefaultHttpClient")
        } catch (_: Throwable) {
            return
        }

        // DefaultHttpClient() 无参构造
        try {
            val ctor = cls.getDeclaredConstructor()
            XCompat.hookMethod(ctor, after = { _, _, thisObj ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethod null
                thisObj?.let {
                    setObjectField(it, "defaultParams", null)
                    setObjectField(it, "connManager", createSCCM())
                }
                null
            })
        } catch (_: Throwable) {
        }

        // DefaultHttpClient(HttpParams)
        try {
            val ctor = cls.getDeclaredConstructor(org.apache.http.params.HttpParams::class.java)
            XCompat.hookMethod(ctor, after = { _, args, thisObj ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethod null
                thisObj?.let {
                    setObjectField(it, "defaultParams", args[0])
                    setObjectField(it, "connManager", createSCCM())
                }
                null
            })
        } catch (_: Throwable) {
        }

        // DefaultHttpClient(ClientConnectionManager, HttpParams)
        try {
            val ctor = cls.getDeclaredConstructor(
                ClientConnectionManager::class.java,
                org.apache.http.params.HttpParams::class.java,
            )
            XCompat.hookMethod(ctor, after = { _, args, thisObj ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethod null
                thisObj?.let {
                    val params = args[1] as org.apache.http.params.HttpParams
                    setObjectField(it, "defaultParams", params)
                    setObjectField(
                        it, "connManager",
                        determineConnManager(args[0] ?: return@hookMethod null, params)
                    )
                }
                null
            })
        } catch (_: Throwable) {
        }

        Logger.d("DefaultHttpClient 构造函数 → 已 Hook")
    }

    private fun hookSSLSocketFactory(classLoader: ClassLoader) {
        val cls = try {
            classLoader.loadClass("org.apache.http.conn.ssl.SSLSocketFactory")
        } catch (_: Throwable) {
            return
        }

        // SSLSocketFactory 构造函数
        try {
            val ctor = cls.getDeclaredConstructor(
                String::class.java, KeyStore::class.java, String::class.java,
                KeyStore::class.java, SecureRandom::class.java,
                org.apache.http.conn.scheme.HostNameResolver::class.java,
            )
            XCompat.hookMethod(ctor, after = { _, args, thisObj ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethod null
                thisObj?.let { obj ->
                    val algorithm = args[0] as String
                    val keystore = args[1] as? KeyStore
                    val keystorePassword = args[2] as? String
                    val random = args[4] as? SecureRandom

                    var keyManagers: Array<KeyManager>? = null
                    if (keystore != null) {
                        keyManagers = callStaticKeyManagers(cls, keystore, keystorePassword)
                    }

                    val trustManagers = TrustAllManager.getBestInstanceArray()
                    val sslContext = SSLContext.getInstance(algorithm)
                    sslContext.init(keyManagers, trustManagers, random)
                    setObjectField(obj, "sslcontext", sslContext)
                    setObjectField(obj, "socketfactory", sslContext.socketFactory)
                }
                null
            })
        } catch (_: Throwable) {
        }

        // SSLSocketFactory.getSocketFactory() → 返回信任一切的实例
        try {
            val m = cls.getDeclaredMethod("getSocketFactory")
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethod null
                TrustAllApacheSSLSocketFactory.getDefault()
            })
        } catch (_: Throwable) {
        }

        // SSLSocketFactory.isSecure(Socket) → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod("isSecure", java.net.Socket::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethod null
                null
            })
        } catch (_: Throwable) {
        }

        Logger.d("Apache SSLSocketFactory → 已 Hook")
    }

    private fun callStaticKeyManagers(cls: Class<*>, keystore: KeyStore, password: String?): Array<KeyManager>? {
        return try {
            val m = cls.getDeclaredMethod(
                "createKeyManagers", KeyStore::class.java, String::class.java
            ).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            m.invoke(null, keystore, password) as Array<KeyManager>
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookSchemeRegistry(classLoader: ClassLoader) {
        // 替换 HTTPS scheme
        try {
            val cls = classLoader.loadClass("org.apache.http.conn.scheme.SchemeRegistry")
            val m = cls.getDeclaredMethod("register", Scheme::class.java)
            XCompat.hookMethodArgs(m) { args ->
                if (!HookPrefs.isHookActive("apache")) return@hookMethodArgs null
                val scheme = args[0] as Scheme
                if (scheme.name == "https") {
                    arrayOf(Scheme("https", TrustAllApacheSSLSocketFactory.getDefault(), 443))
                } else null
            }
        } catch (_: Throwable) {
        }
    }

    // === 辅助方法 ===

    private fun createSCCM(): ClientConnectionManager? {
        return try {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
            trustStore.load(null, null)
            val sf = TrustAllApacheSSLSocketFactory(trustStore)
            sf.hostnameVerifier = SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER
            val registry = SchemeRegistry()
            registry.register(Scheme("http", PlainSocketFactory.getSocketFactory(), 80))
            registry.register(Scheme("https", sf, 443))
            SingleClientConnManager(null, registry)
        } catch (_: Exception) {
            null
        }
    }

    private fun createTSCCM(params: org.apache.http.params.HttpParams): ClientConnectionManager? {
        return try {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
            trustStore.load(null, null)
            val sf = TrustAllApacheSSLSocketFactory(trustStore)
            sf.hostnameVerifier = SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER
            val registry = SchemeRegistry()
            registry.register(Scheme("http", PlainSocketFactory.getSocketFactory(), 80))
            registry.register(Scheme("https", sf, 443))
            ThreadSafeClientConnManager(params, registry)
        } catch (_: Exception) {
            null
        }
    }

    private fun determineConnManager(original: Any, params: org.apache.http.params.HttpParams): ClientConnectionManager? {
        return when (original.javaClass.simpleName) {
            "SingleClientConnManager" -> createSCCM()
            "ThreadSafeClientConnManager" -> createTSCCM(params)
            else -> null
        }
    }

    private fun hasApacheHttpClient(): Boolean {
        return try {
            ApacheHttpHook::class.java.classLoader?.let {
                Class.forName("org.apache.http.impl.client.DefaultHttpClient", false, it)
            } != null
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
