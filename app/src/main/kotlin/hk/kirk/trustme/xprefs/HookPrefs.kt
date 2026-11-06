package hk.kirk.trustme.xprefs

import android.content.SharedPreferences

/**
 * Hook 端全局配置快捷入口（单例桥）
 *
 * Hook 回调中的开关检查：
 * ```kotlin
 * if (!HookPrefs.isHookActive("okhttp")) return
 * ```
 *
 * Modern API：由 MainHook 在 onModuleLoaded 时注入 RemotePreferences
 */
object HookPrefs {
    private const val PREFS_NAME = "trustme_prefs"

    /** Hook 端远程首选项，由 MainHook 注入 */
    @Volatile
    internal var remote: SharedPreferences? = null

    private val prefs = XposedPrefs.HookSide(PREFS_NAME)

    /** prefs 是否可读 */
    val isAvailable: Boolean get() = remote != null && prefs.isAvailable

    /** 全局开关是否开启 */
    fun isEnabled(): Boolean = prefs.getBoolean("enabled", true)

    /** 日志开关是否开启 */
    fun isLoggingEnabled(): Boolean = prefs.getBoolean("logging", true)

    /** 指定 Hook 模块是否处于激活状态（全局开关 && 模块开关） */
    fun isHookActive(hookName: String): Boolean {
        return prefs.getBoolean("enabled", true) && prefs.getBoolean("hook_$hookName", true)
    }

    /** 重新加载配置（远程首选项实时同步，保留兼容接口） */
    fun reload() = prefs.reload()

    /** MainHook 注入入口 */
    fun attach(sp: SharedPreferences?) {
        remote = sp
        prefs.remote = sp
    }

    /** getBoolean 转发（供 MainHook 使用） */
    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
}
