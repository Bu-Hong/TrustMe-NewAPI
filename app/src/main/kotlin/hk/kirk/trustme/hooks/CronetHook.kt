package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs

/**
 * Chromium Cronet 引擎 Hook
 * Cronet 底层使用 Conscrypt，因此 TrustManagerImplHook 通常已覆盖。
 * 这里额外 Hook Cronet 特有的 Public Key Pinning 配置。
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object CronetHook {

    fun hook(classLoader: ClassLoader) {
        // enablePublicKeyPinningBypassForLocalTrustAnchors(boolean) → 设为 true
        try {
            val cls = classLoader.loadClass("org.chromium.net.CronetEngine\$Builder")
            val m = cls.getDeclaredMethod(
                "enablePublicKeyPinningBypassForLocalTrustAnchors",
                Boolean::class.javaPrimitiveType,
            )
            XCompat.hookMethodArgs(m) { args ->
                if (!HookPrefs.isHookActive("cronet")) return@hookMethodArgs null
                Logger.d("CronetEngine.Builder.enablePublicKeyPinningBypass → true")
                arrayOf(true)
            }
        } catch (_: ClassNotFoundException) {
            Logger.d("Cronet 未发现，跳过")
        } catch (e: Throwable) {
            Logger.e("Cronet Hook 失败", e)
        }

        // 尝试 Hook impl 类
        try {
            val cls = classLoader.loadClass("org.chromium.net.impl.CronetEngineBuilderImpl")
            val m = cls.getDeclaredMethod(
                "enablePublicKeyPinningBypassForLocalTrustAnchors",
                Boolean::class.javaPrimitiveType,
            )
            XCompat.hookMethodArgs(m) { args ->
                if (!HookPrefs.isHookActive("cronet")) return@hookMethodArgs null
                arrayOf(true)
            }
        } catch (_: Throwable) {
        }
    }
}
