package hk.kirk.trustme.utils

import hk.kirk.trustme.XCompat

/**
 * 日志工具 — 封装 Modern API log() + 文件日志
 *
 * 旧版 XposedBridge.log 在 API 102 已不可用，改由 XCompat 转发到 XposedInterface.log()
 */
object Logger {

    private const val TAG = "TrustMe"
    private var loggingEnabled = true

    /** 当前进程的 XposedInterface 引用，onModuleLoaded 时注入 */
    @Volatile
    private var xposed: hk.kirk.trustme.XCompat? = null

    /** 当前正在执行的 Hook 模块名称，由 hookSafely 设置 */
    var currentHook: String = "general"

    fun attach(x: hk.kirk.trustme.XCompat) {
        xposed = x
    }

    fun setLoggingEnabled(enabled: Boolean) {
        loggingEnabled = enabled
    }

    /** 始终输出的日志（模块加载、关键状态） */
    fun i(message: String) {
        xposed?.logInfo(TAG, "[$TAG] $message")
    }

    /** 仅在日志开关打开时输出（Hook 触发），自动使用 currentHook */
    fun d(message: String) {
        if (loggingEnabled) {
            xposed?.logInfo(TAG, "[$TAG] $message")
            try {
                LogFileWriter.write(currentHook, message)
            } catch (_: Throwable) {}
        }
    }

    /** 错误日志（始终输出） */
    fun e(message: String, throwable: Throwable? = null) {
        xposed?.logError(TAG, "[$TAG] ERROR: $message", throwable)
    }
}
