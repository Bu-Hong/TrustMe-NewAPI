package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import java.security.cert.X509Certificate

/**
 * Android 7+ Network Security Config Hook
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object NetworkSecurityConfigHook {

    fun hook(classLoader: ClassLoader) {
        val cls = try {
            classLoader.loadClass("android.security.net.config.NetworkSecurityTrustManager")
        } catch (_: Throwable) {
            return
        }

        // checkPins(List) → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod("checkPins", List::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("nsc")) return@hookMethod null
                null
            })
            Logger.d("NetworkSecurityTrustManager.checkPins → 已绕过")
        } catch (_: Throwable) {
        }

        // checkServerTrusted(X509Certificate[], String) → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod(
                "checkServerTrusted",
                Array<X509Certificate>::class.java, String::class.java,
            )
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("nsc")) return@hookMethod null
                null
            })
            Logger.d("NetworkSecurityTrustManager.checkServerTrusted → 已绕过")
        } catch (_: Throwable) {
        }
    }
}
