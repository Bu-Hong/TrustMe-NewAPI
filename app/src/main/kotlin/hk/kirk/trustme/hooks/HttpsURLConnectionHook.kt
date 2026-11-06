package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory

/**
 * HttpsURLConnection Hook
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object HttpsURLConnectionHook {

    fun hook(classLoader: ClassLoader) {
        val cls = try {
            classLoader.loadClass("javax.net.ssl.HttpsURLConnection")
        } catch (e: Throwable) {
            Logger.e("HttpsURLConnection 类未找到", e)
            return
        }

        // setDefaultHostnameVerifier → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod("setDefaultHostnameVerifier", HostnameVerifier::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("urlconnection")) return@hookMethod null
                Logger.d("HttpsURLConnection.setDefaultHostnameVerifier → 已无效化")
                null // void 方法，返回 null 等价旧版 param.result = null
            })
            Logger.d("HttpsURLConnection.setDefaultHostnameVerifier → 已无效化")
        } catch (e: Throwable) {
            Logger.e("setDefaultHostnameVerifier Hook 失败", e)
        }

        // setSSLSocketFactory → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod("setSSLSocketFactory", SSLSocketFactory::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("urlconnection")) return@hookMethod null
                Logger.d("HttpsURLConnection.setSSLSocketFactory → 已无效化")
                null
            })
            Logger.d("HttpsURLConnection.setSSLSocketFactory → 已无效化")
        } catch (e: Throwable) {
            Logger.e("setSSLSocketFactory Hook 失败", e)
        }

        // setHostnameVerifier → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod("setHostnameVerifier", HostnameVerifier::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("urlconnection")) return@hookMethod null
                Logger.d("HttpsURLConnection.setHostnameVerifier → 已无效化")
                null
            })
            Logger.d("HttpsURLConnection.setHostnameVerifier → 已无效化")
        } catch (e: Throwable) {
            Logger.e("setHostnameVerifier Hook 失败", e)
        }
    }
}
