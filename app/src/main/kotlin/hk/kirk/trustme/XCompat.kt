package hk.kirk.trustme

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

/**
 * 兼容桥：将旧版 XposedBridge / XposedHelpers 的调用习惯翻译为 Modern API。
 *
 * - XposedBridge.hookMethod(member, callback) → hook(member).intercept(...)
 * - beforeHookedMethod / afterHookedMethod → intercept 前置 / proceed 后置
 * - param.result = x → 拦截并返回 x（不调用 proceed）
 * - param.args[i] = x → proceed(arrayOf(...))
 */
object XCompat {

    private var base: XposedInterface? = null

    /** onModuleLoaded 时由 MainHook 注入 */
    fun attach(x: XposedInterface) {
        base = x
    }

    private fun x(): XposedInterface =
        base ?: throw IllegalStateException("XposedInterface not attached")

    fun logInfo(tag: String, msg: String) =
        x().log(Log.INFO, tag, msg)

    fun logError(tag: String, msg: String, tr: Throwable? = null) =
        x().log(Log.ERROR, tag, msg, tr)

    /**
     * Hook 一个成员方法/构造函数，提供类似旧版 XC_MethodHook 的 before/after 语义。
     *
     * before 返回非 null 表示替换返回值（等价旧版 beforeHookedMethod 中设置 param.result）；
     * before 返回 null 表示继续执行原方法。
     */
    fun hookMethod(
        member: Member,
        before: ((args: List<Any?>, thisObject: Any?) -> Any?)? = null,
        after: ((result: Any?, args: List<Any?>, thisObject: Any?) -> Any?)? = null,
    ) {
        x().hook(member).intercept { chain ->
            val args = chain.args
            val self = chain.thisObject
            val replaced = before?.invoke(args, self)
            if (replaced != null) {
                // 旧版语义：before 阶段直接设置 param.result，短路原方法
                return@intercept replaced
            }
            var result = chain.proceed()
            if (after != null) {
                result = after(result, args, self)
            }
            result
        }
    }

    /**
     * 仅修改参数后继续原调用（等价旧版在 beforeHookedMethod 中改 param.args）。
     * transform 返回新的参数数组；返回 null 表示不修改。
     */
    fun hookMethodArgs(
        member: Member,
        transform: (args: List<Any?>) -> Array<Any?>?,
    ) {
        x().hook(member).intercept { chain ->
            val newArgs = transform(chain.args)
            if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
        }
    }
}
