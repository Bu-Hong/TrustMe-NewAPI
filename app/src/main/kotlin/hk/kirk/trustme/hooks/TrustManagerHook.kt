package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.trust.TrustAllManager
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import java.security.cert.X509Certificate

/**
 * TrustManager / TrustManagerFactory Hook
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object TrustManagerHook {

    fun hook(classLoader: ClassLoader) {
        // Hook TrustManagerFactory.getTrustManagers()
        // 只替换非系统 TrustManager（系统的由专门 Hook 处理）
        try {
            val cls = classLoader.loadClass("javax.net.ssl.TrustManagerFactory")
            val getTrustManagers = cls.getDeclaredMethod("getTrustManagers")
            XCompat.hookMethod(
                getTrustManagers,
                after = { result, _, _ ->
                    if (!HookPrefs.isHookActive("trustmanager")) return@hookMethod result

                    val managers = result as? Array<*>
                    if (managers == null || managers.isEmpty()) return@hookMethod result

                    val firstManager = managers[0] ?: return@hookMethod result
                    val className = firstManager.javaClass.name

                    // 跳过系统/平台级 TrustManager —— 它们由专门的 Hook 处理：
                    // - com.android.org.conscrypt.TrustManagerImpl → TrustManagerImplHook
                    // - android.security.net.config.* → NetworkSecurityConfigHook
                    // 替换系统 TrustManager 会导致 WebView (Chromium) 等系统组件 SSL 初始化失败
                    if (className.startsWith("com.android.") ||
                        className.startsWith("android.")
                    ) {
                        Logger.d("TrustManagerFactory.getTrustManagers() → 跳过系统 TrustManager: $className")
                        return@hookMethod result
                    }

                    Logger.d("TrustManagerFactory.getTrustManagers() → 已替换 (原始: $className)")
                    TrustAllManager.getBestInstanceArray()
                }
            )
        } catch (e: Throwable) {
            Logger.e("TrustManagerFactory Hook 失败", e)
        }

        // Hook X509TrustManagerExtensions.checkServerTrusted(chain, authType, host)
        try {
            val cls = classLoader.loadClass("android.net.http.X509TrustManagerExtensions")
            val check = cls.getDeclaredMethod(
                "checkServerTrusted",
                Array<X509Certificate>::class.java,
                String::class.java,
                String::class.java,
            )
            XCompat.hookMethod(
                check,
                before = { args, _ ->
                    if (!HookPrefs.isHookActive("trustmanager")) return@hookMethod null

                    // 返回类型必须是 List<X509Certificate>，不能直接返回 Array
                    @Suppress("UNCHECKED_CAST")
                    val chain = args[0] as? Array<X509Certificate>
                    Logger.d("X509TrustManagerExtensions.checkServerTrusted() → 已绕过")
                    chain?.toList() ?: emptyList<X509Certificate>()
                }
            )
        } catch (e: Throwable) {
            Logger.e("X509TrustManagerExtensions Hook 失败", e)
        }
    }
}
