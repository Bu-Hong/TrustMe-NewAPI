package hk.kirk.trustme.hooks

import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs

/**
 * Conscrypt PinManager Hook
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object PinManagerHook {

    private const val PIN_MANAGER_CLASS = "com.android.org.conscrypt.PinManager"

    fun hook(classLoader: ClassLoader) {
        val cls = try {
            classLoader.loadClass(PIN_MANAGER_CLASS)
        } catch (_: Throwable) {
            // PinManager 不存在，正常情况
            return
        }

        // isChainValid(String, List) → return true
        try {
            val m = cls.getDeclaredMethod("isChainValid", String::class.java, List::class.java)
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("pinmanager")) return@hookMethod null
                true
            })
            Logger.d("PinManager.isChainValid → 已绕过")
        } catch (_: NoSuchMethodException) {
        } catch (_: Throwable) {
        }

        // 动态查找并 Hook 所有 checkPins 方法
        for (method in cls.declaredMethods) {
            if (method.name == "checkPins") {
                try {
                    XCompat.hookMethod(method, before = { _, _ ->
                        if (!HookPrefs.isHookActive("pinmanager")) return@hookMethod null
                        null
                    })
                    Logger.d("PinManager.checkPins → 已绕过")
                } catch (_: Throwable) {
                }
            }
        }
    }
}
