package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.security.cert.X509Certificate
import java.util.ArrayList
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

/**
 * Conscrypt TrustManagerImpl Hook — 双策略融合
 *
 * 策略A：硬编码 Hook 已知方法签名
 * 策略B：反射动态发现 checkTrustedRecursive 所有重载
 * 额外：Hook verifyChain
 *
 * Modern API：hook(Executable).intercept(Chain)
 * 注：旧版的 Zygote 级别 Hook（initZygote）在 API 101+ 已不可能（模块不再注入 Zygote），
 *     统一在 App 进程内安装，功能等价。
 */
object TrustManagerImplHook {

    private const val IMPL_CLASS = "com.android.org.conscrypt.TrustManagerImpl"

    /**
     * 运行时检查开关的替换回调
     * 每次方法被调用时都会检查开关状态：
     * 开关关闭 → proceed 让原始方法正常执行；开关打开 → 拦截并返回空列表
     */
    private fun createDynamicReplacement(method: Method) {
        XCompat.hookMethod(
            method,
            before = { _, _ ->
                if (!HookPrefs.isHookActive("conscrypt")) {
                    // 开关关闭，不拦截，让原始方法正常执行（返回 null 继续 proceed）
                    return@hookMethod null
                }
                // 开关打开，拦截并返回空列表
                ArrayList<X509Certificate>()
            }
        )
    }

    /**
     * App 级别 Hook — 使用 JustTrustMe 的硬编码策略 + 动态反射策略
     */
    fun hookApp(classLoader: ClassLoader) {
        if (!hasTrustManagerImpl()) return

        val cls = try {
            classLoader.loadClass(IMPL_CLASS)
        } catch (_: Throwable) {
            return
        }

        // checkServerTrusted(X509Certificate[], String) → return null
        try {
            val m = cls.getDeclaredMethod(
                "checkServerTrusted",
                Array<X509Certificate>::class.java, String::class.java
            )
            XCompat.hookMethod(
                m,
                before = { _, _ ->
                    if (!HookPrefs.isHookActive("conscrypt")) return@hookMethod null
                    Logger.d("TrustManagerImpl.checkServerTrusted(cert[], String) → 已绕过")
                    null
                }
            )
        } catch (_: Throwable) {
        }

        // 动态反射发现所有 checkServerTrusted / checkTrusted / checkTrustedRecursive / verifyChain 重载
        for (method in cls.declaredMethods) {
            try {
                when {
                    isCheckTrustedRecursive(method) -> {
                        createDynamicReplacement(method)
                        Logger.d("Hook: checkTrustedRecursive(${method.parameterTypes.joinToString { it.simpleName }})")
                    }
                    // checkServerTrusted(X509Certificate[], String, String) → return ArrayList()
                    method.name == "checkServerTrusted" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == Array<X509Certificate>::class.java &&
                        method.parameterTypes[1] == String::class.java &&
                        method.parameterTypes[2] == String::class.java -> {
                        createDynamicReplacement(method)
                        Logger.d("Hook: checkServerTrusted(cert[], String, String)")
                    }
                    // checkServerTrusted(X509Certificate[], String, SSLSession) → return ArrayList()
                    method.name == "checkServerTrusted" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == Array<X509Certificate>::class.java &&
                        method.parameterTypes[1] == String::class.java &&
                        method.parameterTypes[2] == SSLSession::class.java -> {
                        createDynamicReplacement(method)
                        Logger.d("Hook: checkServerTrusted(cert[], String, SSLSession)")
                    }
                    // checkTrusted(X509Certificate[], String, SSLSession, SSLParameters, boolean)
                    // checkTrusted(X509Certificate[], byte[], byte[], String, String, boolean)
                    method.name == "checkTrusted" &&
                        method.parameterTypes.isNotEmpty() &&
                        method.parameterTypes[0] == Array<X509Certificate>::class.java &&
                        (method.parameterTypes.contains(SSLParameters::class.java) ||
                            method.parameterTypes.contains(ByteArray::class.java)) -> {
                        createDynamicReplacement(method)
                        Logger.d("Hook: checkTrusted(${method.parameterTypes.joinToString { it.simpleName }})")
                    }
                    // verifyChain
                    method.name == "verifyChain" &&
                        List::class.java.isAssignableFrom(method.returnType) -> {
                        createDynamicReplacement(method)
                        Logger.d("TrustManagerImpl.verifyChain() → 已 Hook")
                    }
                }
            } catch (_: Throwable) {
            }
        }

        Logger.d("TrustManagerImplHook (App 级别) 已安装")
    }

    /**
     * 检查方法是否为 checkTrustedRecursive 的某个重载
     */
    private fun isCheckTrustedRecursive(method: Method): Boolean {
        if (method.name != "checkTrustedRecursive") return false
        if (!List::class.java.isAssignableFrom(method.returnType)) return false

        val returnType = method.genericReturnType
        if (returnType !is ParameterizedType) return false

        val args = returnType.actualTypeArguments
        return args.size == 1 && args[0] == X509Certificate::class.java
    }

    private fun hasTrustManagerImpl(): Boolean {
        return try {
            Class.forName(IMPL_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
