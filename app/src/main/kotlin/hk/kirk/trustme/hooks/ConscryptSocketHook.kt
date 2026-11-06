package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs

/**
 * Conscrypt Socket 层验证 Hook
 *
 * 这些是比 TrustManagerImpl 更底层的 Hook 点，
 * 直接在 SSL Socket 层面绕过证书链验证。
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object ConscryptSocketHook {

    fun hook(classLoader: ClassLoader) {
        // OpenSSLSocketImpl.verifyCertificateChain — 旧版 Android
        hookVerifyCertificateChain(
            classLoader,
            "com.android.org.conscrypt.OpenSSLSocketImpl"
        )

        // ConscryptFileDescriptorSocket.verifyCertificateChain — Android 12+
        hookVerifyCertificateChain(
            classLoader,
            "com.android.org.conscrypt.ConscryptFileDescriptorSocket"
        )

        // Platform.checkServerTrusted
        hookPlatformCheckServerTrusted(classLoader)
    }

    private fun hookVerifyCertificateChain(classLoader: ClassLoader, className: String) {
        try {
            val cls = classLoader.loadClass(className)
            // 查找所有名为 verifyCertificateChain 的方法并 Hook
            for (method in cls.declaredMethods) {
                if (method.name == "verifyCertificateChain") {
                    try {
                        XCompat.hookMethod(method, before = { _, _ ->
                            if (!HookPrefs.isHookActive("conscryptsocket")) return@hookMethod null
                            null
                        })
                        Logger.d("$className.verifyCertificateChain → 已绕过")
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
            // 类不存在，正常情况（不同 Android 版本）
        }
    }

    private fun hookPlatformCheckServerTrusted(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("com.android.org.conscrypt.Platform")
            for (method in cls.declaredMethods) {
                if (method.name == "checkServerTrusted") {
                    try {
                        XCompat.hookMethod(method, before = { _, _ ->
                            if (!HookPrefs.isHookActive("conscryptsocket")) return@hookMethod null
                            null
                        })
                        Logger.d("Conscrypt Platform.checkServerTrusted → 已绕过")
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
