package hk.kirk.trustme.utils

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * 文件日志写入器 — 将结构化日志写入 JSONL 文件
 *
 * 写入路径: /data/data/hk.kirk.trustme/shared_prefs/trustme_logs.jsonl
 * 写入发生在 Hook 端进程（无 context），因此仍使用硬编码模块数据目录；
 * 该文件日志方案与 Modern API 无关，在新版框架下继续可用。
 */
object LogFileWriter {

    private const val MODULE_PACKAGE = "hk.kirk.trustme"
    private const val LOG_FILE = "trustme_logs.jsonl"
    private const val MAX_LINES = 500
    private val lock = Any()

    /** 日志目录路径 — 通过标准 Android 数据路径定位 */
    private val logDir: File
        get() = File("/data/data/$MODULE_PACKAGE/shared_prefs")

    private var currentApp: String = ""

    /** 设置当前被 Hook 的包名 */
    fun init(packageName: String) {
        currentApp = packageName
    }

    /** 写入一条日志 */
    fun write(hook: String, msg: String) {
        write(currentApp, hook, msg)
    }

    fun write(app: String, hook: String, msg: String) {
        try {
            synchronized(lock) {
                val dir = logDir
                if (!dir.exists()) return // 模块未安装则跳过
                val file = File(dir, LOG_FILE)
                val entry = JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    put("app", app.ifEmpty { "unknown" })
                    put("hook", hook)
                    put("msg", msg)
                }
                file.appendText(entry.toString() + "\n")
                // 设置世界可读
                try { file.setReadable(true, false) } catch (_: Throwable) {}
                truncateIfNeeded(file)
            }
        } catch (_: Throwable) {
            // 静默失败
        }
    }

    private fun truncateIfNeeded(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        val keep = lines.subList(lines.size - MAX_LINES / 2, lines.size)
        BufferedWriter(FileWriter(file, false)).use { writer ->
            keep.forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }
    }
}
