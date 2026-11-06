package hk.kirk.trustme.xprefs

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

/**
 * Xposed 模块配置存储桥（Modern API / Remote Preferences）
 *
 * 旧版 XSharedPreferences / MODE_WORLD_READABLE / 手动改文件权限在 targetApiVersion=102
 * 下已不可用（模块不能调用 legacy de.robv API）。按官方文档迁移到远程首选项：
 * - 模块 UI 端：通过 libxposed service（XposedServiceHelper）拿到 XposedService，
 *   用 service.getRemotePreferences(group) 读写
 * - Hook 端：通过 XposedInterface.getRemotePreferences(group) 只读
 *
 * 用法：
 * - 模块 UI 端: `XposedPrefs.module(context, "my_prefs")`  → 读/写
 * - Hook 端:    `XposedPrefs.hook("com.example.module", "my_prefs")` → 只读
 */
sealed class XposedPrefs {

    abstract fun getBoolean(key: String, default: Boolean = false): Boolean
    abstract fun getString(key: String, default: String = ""): String
    abstract fun getInt(key: String, default: Int = 0): Int
    abstract fun getLong(key: String, default: Long = 0L): Long
    abstract fun getFloat(key: String, default: Float = 0f): Float
    abstract fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String>
    abstract fun contains(key: String): Boolean
    abstract fun all(): Map<String, *>

    /** 当前存储是否可用 */
    abstract val isAvailable: Boolean

    companion object {
        /**
         * 创建模块 UI 端实例（读写）
         *
         * @param context Activity/Application 的 Context
         * @param prefsName 远程首选项 group 名
         */
        fun module(context: Context, prefsName: String): ModuleSide =
            ModuleSide(context, prefsName)

        /**
         * 创建 Hook（宿主进程）端实例（只读）
         *
         * @param modulePackageName 模块的包名（仅为兼容旧签名保留）
         * @param prefsName 远程首选项 group 名
         */
        fun hook(modulePackageName: String, prefsName: String): HookSide =
            HookSide(prefsName)
    }

    // ========================================================================
    // 服务绑定 — 模块 App 端通过 libxposed service 与框架通信
    // ========================================================================

    object ServiceHolder {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        /** 在 Application.onCreate 中调用一次 */
        fun register() {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    ServiceHolder.service = service
                    listeners.forEach { it(service) }
                }

                override fun onServiceDied(service: XposedService) {
                    ServiceHolder.service = null
                    listeners.forEach { it(null) }
                }
            })
        }

        fun addListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            service?.let(listener)
        }
    }

    // ========================================================================
    // 模块 UI 端 — 读写（远程首选项 + 本地镜像）
    // ========================================================================

    class ModuleSide internal constructor(
        context: Context,
        private val prefsName: String,
    ) : XposedPrefs() {

        /** 本地镜像：service 未绑定时保证 UI 状态可读写，绑定后写操作同步到远程 */
        private val sp: SharedPreferences =
            context.getSharedPreferences("${prefsName}_local", Context.MODE_PRIVATE)

        private val remote: SharedPreferences?
            get() = ServiceHolder.service?.getRemotePreferences(prefsName)

        override val isAvailable: Boolean
            get() = ServiceHolder.service != null

        override fun getBoolean(key: String, default: Boolean): Boolean {
            remote?.let { return it.getBoolean(key, sp.getBoolean(key, default)) }
            return sp.getBoolean(key, default)
        }

        override fun getString(key: String, default: String): String {
            remote?.let { return it.getString(key, sp.getString(key, default) ?: default) ?: default }
            return sp.getString(key, default) ?: default
        }

        override fun getInt(key: String, default: Int): Int {
            remote?.let { return it.getInt(key, sp.getInt(key, default)) }
            return sp.getInt(key, default)
        }

        override fun getLong(key: String, default: Long): Long {
            remote?.let { return it.getLong(key, sp.getLong(key, default)) }
            return sp.getLong(key, default)
        }

        override fun getFloat(key: String, default: Float): Float {
            remote?.let { return it.getFloat(key, sp.getFloat(key, default)) }
            return sp.getFloat(key, default)
        }

        override fun getStringSet(key: String, default: Set<String>): Set<String> {
            remote?.let { return it.getStringSet(key, sp.getStringSet(key, default) ?: default) ?: default }
            return sp.getStringSet(key, default) ?: default
        }

        override fun contains(key: String): Boolean {
            remote?.let { return it.contains(key) }
            return sp.contains(key)
        }

        override fun all(): Map<String, *> {
            remote?.let { return it.all }
            return sp.all
        }

        /** 写入 Boolean 值 */
        fun putBoolean(key: String, value: Boolean) = edit { putBoolean(key, value) }

        /** 写入 String 值 */
        fun putString(key: String, value: String) = edit { putString(key, value) }

        /** 写入 Int 值 */
        fun putInt(key: String, value: Int) = edit { putInt(key, value) }

        /** 写入 Long 值 */
        fun putLong(key: String, value: Long) = edit { putLong(key, value) }

        /** 写入 Float 值 */
        fun putFloat(key: String, value: Float) = edit { putFloat(key, value) }

        /** 写入 StringSet 值 */
        fun putStringSet(key: String, value: Set<String>) = edit { putStringSet(key, value) }

        /** 移除指定键 */
        fun remove(key: String) = edit { remove(key) }

        /** 清空所有数据 */
        fun clear() = edit { clear() }

        /** 编辑并提交：远程优先，本地镜像同步 */
        private inline fun edit(crossinline block: SharedPreferences.Editor.() -> Unit) {
            sp.edit().apply { block() }.apply()
            remote?.edit()?.apply { block() }?.apply()
        }
    }

    // ========================================================================
    // Hook（宿主进程）端 — 只读（XposedInterface.getRemotePreferences）
    // ========================================================================

    class HookSide internal constructor(
        private val prefsName: String,
    ) : XposedPrefs() {

        /** Hook 端远程首选项，由 MainHook 在 onModuleLoaded 时注入 */
        @Volatile
        internal var remote: SharedPreferences? = null

        override val isAvailable: Boolean
            get() = remote != null

        /** 兼容旧接口：远程首选项实时读取，无需重载 */
        fun reload() {
            // Remote Preferences 由框架实时同步，无需 reload
        }

        override fun getBoolean(key: String, default: Boolean) = remote?.getBoolean(key, default) ?: default
        override fun getString(key: String, default: String) = remote?.getString(key, default) ?: default
        override fun getInt(key: String, default: Int) = remote?.getInt(key, default) ?: default
        override fun getLong(key: String, default: Long) = remote?.getLong(key, default) ?: default
        override fun getFloat(key: String, default: Float) = remote?.getFloat(key, default) ?: default
        override fun getStringSet(key: String, default: Set<String>) = remote?.getStringSet(key, default) ?: default
        override fun contains(key: String) = remote?.contains(key) ?: false
        override fun all(): Map<String, *> = remote?.all ?: emptyMap<String, Any>()
    }
}
