package hk.kirk.trustme

import hk.kirk.trustme.hooks.ApacheHttpHook
import hk.kirk.trustme.hooks.ConscryptSocketHook
import hk.kirk.trustme.hooks.CronetHook
import hk.kirk.trustme.hooks.HttpsURLConnectionHook
import hk.kirk.trustme.hooks.NetworkSecurityConfigHook
import hk.kirk.trustme.hooks.OkHttpHook
import hk.kirk.trustme.hooks.PinManagerHook
import hk.kirk.trustme.hooks.SSLContextHook
import hk.kirk.trustme.hooks.ThirdPartyHook
import hk.kirk.trustme.hooks.TrustManagerHook
import hk.kirk.trustme.hooks.TrustManagerImplHook
import hk.kirk.trustme.hooks.WebViewHook
import hk.kirk.trustme.utils.LogFileWriter
import hk.kirk.trustme.utils.Logger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * TrustMe main entry point (libxposed modern API 102)
 *
 * 旧版 API（被移除/失效，已按官方文档迁移）：
 * - IXposedHookZygoteInit / initZygote —— API 101 起模块不再注入 Zygote，改为在每个作用域进程内加载（onModuleLoaded）
 * - IXposedHookLoadPackage / handleLoadPackage —— 改为 onPackageLoaded(PackageLoadedParam)
 * - de.robv.android.xposed.XposedHelpers.findAndHookMethod —— 框架不再提供，改为 hook(Executable).intercept(Chain)
 * - de.robv.android.xposed.XC_MethodHook —— 改为 XposedInterface.Hooker 拦截器链模型
 * - de.robv.android.xposed.XposedBridge.hookMethod —— 改为 hook(Executable)
 * - XposedBridge.log —— 改为 log(priority, tag, msg)
 * - assets/xposed_init —— 改为 META-INF/xposed/java_init.list
 *
 * 保留（新版 API 仍然可用 / 迁移后的替代实现）：
 * - 模块配置读取迁移到 Remote Preferences（Modern API 首选方案，见 xprefs/XposedPrefs.kt）
 */
class MainHook : XposedModule() {

    companion object {
        private const val TAG = "TrustMe"
        private const val MY_PACKAGE = "hk.kirk.trustme"
        private const val PREFS_NAME = "trustme_prefs"
    }

    /** Hook 端的配置桥 — HookPrefs 单例（RemotePreferences 注入后共享） */
    private val prefs get() = hk.kirk.trustme.xprefs.HookPrefs

    // ==========================================
    // 模块加载（替代旧版 initZygote 入口）
    // API 101 起，模块在作用域 App 进程内加载，不再有 Zygote 阶段
    // ==========================================

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(android.util.Log.INFO, TAG, "onModuleLoaded — process: ${param.processName}")
        log(
            android.util.Log.INFO, TAG,
            "framework: $frameworkName ($frameworkVersionCode) API $apiVersion"
        )
        Logger.attach(this) // XposedModule implements XposedInterface

        // 注入远程首选项到 Hook 端配置桥（Modern API: getRemotePreferences）
        try {
            hk.kirk.trustme.xprefs.HookPrefs.attach(getRemotePreferences(PREFS_NAME))
            log(android.util.Log.INFO, TAG, "RemotePreferences 已注入 (可读: ${prefs.isAvailable})")
        } catch (t: Throwable) {
            log(android.util.Log.WARN, TAG, "RemotePreferences 注入失败（框架可能不支持远程能力）", t)
        }
    }

    // ==========================================
    // 每个 App 包加载时执行（替代旧版 handleLoadPackage）
    // ==========================================

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)

        val packageName = param.packageName

        // 重新加载配置
        prefs.reload()

        // 检查全局开关
        if (!prefs.getBoolean("enabled", true)) {
            log(android.util.Log.INFO, TAG, "全局开关已关闭，跳过 $packageName (prefs可读: ${prefs.isAvailable})")
            return
        }

        // 更新日志开关
        Logger.setLoggingEnabled(prefs.getBoolean("logging", true))

        log(android.util.Log.DEBUG, TAG, "处理 App: $packageName")
        LogFileWriter.init(packageName)

        val cl = param.getDefaultClassLoader()

        // ===== 系统级 Hook（直接在当前 ClassLoader 中 Hook） =====

        // 1. SSLContext.init — 底层替换 TrustManager
        hookSafely("SSLContext") { SSLContextHook.hook(cl) }

        // 2. TrustManager / TrustManagerFactory
        hookSafely("TrustManager") { TrustManagerHook.hook(cl) }

        // 3. Conscrypt TrustManagerImpl（App 级别的硬编码 Hook）
        //    旧版通过 initZygote 无 ClassLoader 反射 Hook，API 101+ 无法再注入 Zygote，
        //    统一在 App 进程内完成（本回调时机足够早，功能等价）
        hookSafely("Conscrypt") { TrustManagerImplHook.hookApp(cl) }

        // 4. HttpsURLConnection
        hookSafely("URLConnection") { HttpsURLConnectionHook.hook(cl) }

        // 5. WebView
        hookSafely("WebView") { WebViewHook.hook(cl) }

        // 6. Network Security Config (Android 7+)
        hookSafely("NSC") { NetworkSecurityConfigHook.hook(cl) }

        // 7. Conscrypt Socket 层
        hookSafely("ConscryptSocket") { ConscryptSocketHook.hook(cl) }

        // 8. Conscrypt PinManager
        hookSafely("PinManager") { PinManagerHook.hook(cl) }

        // ===== Multi-dex 支持：通过 Application.attach 延迟加载第三方库 Hook =====
        try {
            val appClass = cl.loadClass("android.app.Application")
            val attach = appClass.getDeclaredMethod("attach", android.content.Context::class.java)
            hook(attach).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context = chain.args[0] as android.content.Context
                    val appClassLoader = context.classLoader

                    // 9. OkHttp (2.x / 3.x / 4.x+)
                    hookSafely("OkHttp") { OkHttpHook.hook(appClassLoader, context.applicationInfo) }

                    // 10. Apache HTTP Client
                    hookSafely("Apache") { ApacheHttpHook.hook(appClassLoader) }

                    // 11. Cronet
                    hookSafely("Cronet") { CronetHook.hook(appClassLoader) }

                    // 12. 第三方库 (xutils, httpclientandroidlib)
                    hookSafely("ThirdParty") { ThirdPartyHook.hook(appClassLoader) }
                } catch (t: Throwable) {
                    Logger.e("Application.attach 后置 Hook 失败", t)
                }
                result
            }
        } catch (t: Throwable) {
            Logger.e("Hook Application.attach 失败", t)

            // 回退：直接在当前 ClassLoader 中尝试
            hookSafely("OkHttp") { OkHttpHook.hook(cl, param.applicationInfo) }
            hookSafely("Apache") { ApacheHttpHook.hook(cl) }
            hookSafely("Cronet") { CronetHook.hook(cl) }
            hookSafely("ThirdParty") { ThirdPartyHook.hook(cl) }
        }
    }

    /**
     * 安全执行 Hook，单个 Hook 失败不影响其他模块
     */
    private fun hookSafely(hookName: String, block: () -> Unit) {
        if (!prefs.getBoolean("hook_${hookName.lowercase()}", true)) {
            log(android.util.Log.DEBUG, TAG, "$hookName Hook 已禁用，跳过")
            return
        }
        try {
            Logger.currentHook = hookName
            block()
        } catch (e: Throwable) {
            Logger.e("$hookName Hook 失败: ${e.message}", e)
        }
    }
}
