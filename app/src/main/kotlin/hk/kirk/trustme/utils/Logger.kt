package hk.kirk.trustme.utils

/**
 * 日志工具 — 封装 Modern API log() + 文件日志
 *
 * 旧版 XposedBridge.log 在 API 102 已不可用，改由 XCompat 转发到 XposedInterface.log()
 */
object Logger {

    private const val TAG = "TrustMe"
    private var loggingEnabled = true

    /** 当前正在执行的 Hook 模块名称，由 hookSafely 设置 */
    var currentHook: String = "general"

    /** onModuleLoaded 时注入（XposedModule 本身实现 XposedInterface） */
    fun attach(x: io.github.libxposed.api.XposedInterface) {
        hk.kirk.trustme.XCompat.attach(x)
    }

    fun setLoggingEnabled(enabled: Boolean) {
        loggingEnabled = enabled
    }

    /** 始终输出的日志（模块加载、关键状态） */
    fun i(message: String) {
        hk.kirk.trustme.XCompat.logInfo(TAG, "[$TAG] $message")
    }

    /** 仅在日志开关打开时输出（Hook 触发），自动使用 currentHook */
    fun d(message: String) {
        if (loggingEnabled) {
            hk.kirk.trustme.XCompat.logInfo(TAG, "[$TAG] $message")
            try {
                LogFileWriter.write(currentHook, message)
            } catch (_: Throwable) {}
        }
    }

    /** 错误日志（始终输出） */
    fun e(message: String, throwable: Throwable? = null) {
        hk.kirk.trustme.XCompat.logError(TAG, "[$TAG] ERROR: $message", throwable)
    }
}
