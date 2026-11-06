package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.trust.TrustAllManager
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * SSLContext.init() 底层替换
 *
 * 这是最核心的 Hook 之一 —— 几乎所有 HTTPS 连接最终都会通过 SSLContext.init() 设置 TrustManager。
 * 通过在 init() 调用前替换 TrustManager 参数，实现全局的证书信任。
 *
 * Modern API：hook(Executable).intercept(Chain) + chain.proceed(newArgs)
 */
object SSLContextHook {

    fun hook(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("javax.net.ssl.SSLContext")
            val init = cls.getDeclaredMethod(
                "init",
                Array<KeyManager>::class.java,
                Array<TrustManager>::class.java,
                SecureRandom::class.java,
            )
            XCompat.hookMethodArgs(init) { args ->
                if (!HookPrefs.isHookActive("sslcontext")) return@hookMethodArgs null
                Logger.d("SSLContext.init() → 已替换 TrustManager")
                arrayOf<Any?>(
                    null,
                    TrustAllManager.getBestInstanceArray(),
                    null,
                )
            }
            Logger.d("SSLContextHook 已安装")
        } catch (e: Throwable) {
            Logger.e("SSLContextHook 安装失败", e)
        }
    }
}
