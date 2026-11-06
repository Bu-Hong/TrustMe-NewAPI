package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.trust.TrustAllHostnameVerifier
import hk.kirk.trustme.trust.TrustAllManager
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * 第三方库 Hook — xutils / httpclientandroidlib
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object ThirdPartyHook {

    fun hook(classLoader: ClassLoader) {
        hookXutils(classLoader)
        hookHttpClientAndroidLib(classLoader)
    }

    /**
     * xutils 3.x HTTP 库
     */
    private fun hookXutils(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("org.xutils.http.RequestParams")

            // setSslSocketFactory → 替换为空 SSLFactory
            val m1 = cls.getDeclaredMethod("setSslSocketFactory", SSLSocketFactory::class.java)
            XCompat.hookMethodArgs(m1) { args ->
                if (!HookPrefs.isHookActive("thirdparty")) return@hookMethodArgs null
                arrayOf<Any?>(createEmptySSLFactory())
            }

            // setHostnameVerifier → 替换为 TrustAllHostnameVerifier
            val m2 = cls.getDeclaredMethod("setHostnameVerifier", HostnameVerifier::class.java)
            XCompat.hookMethodArgs(m2) { args ->
                if (!HookPrefs.isHookActive("thirdparty")) return@hookMethodArgs null
                arrayOf<Any?>(TrustAllHostnameVerifier.getInstance())
            }

            Logger.d("xutils RequestParams → 已 Hook")
        } catch (_: ClassNotFoundException) {
            Logger.d("xutils 未发现，跳过")
        } catch (e: Throwable) {
            Logger.e("xutils Hook 失败", e)
        }
    }

    /**
     * httpclientandroidlib (ch.boye)
     */
    private fun hookHttpClientAndroidLib(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("ch.boye.httpclientandroidlib.conn.ssl.AbstractVerifier")
            val m = cls.getDeclaredMethod(
                "verify",
                String::class.java, Array<String>::class.java,
                Array<String>::class.java, Boolean::class.javaPrimitiveType,
            )
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("thirdparty")) return@hookMethod null
                null
            })
            Logger.d("httpclientandroidlib AbstractVerifier → 已绕过")
        } catch (_: ClassNotFoundException) {
            Logger.d("httpclientandroidlib 未发现，跳过")
        } catch (e: Throwable) {
            Logger.e("httpclientandroidlib Hook 失败", e)
        }
    }

    private fun createEmptySSLFactory(): SSLSocketFactory? {
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(TrustAllManager.getInstance()), null)
            sslContext.socketFactory
        } catch (_: Exception) {
            null
        }
    }
}
