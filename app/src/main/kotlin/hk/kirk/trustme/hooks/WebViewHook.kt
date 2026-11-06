package hk.kirk.trustme.hooks

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import hk.kirk.trustme.XCompat
import hk.kirk.trustme.utils.Logger
import hk.kirk.trustme.xprefs.HookPrefs

/**
 * WebView SSL 错误处理 Hook
 *
 * Modern API：hook(Executable).intercept(Chain)
 */
object WebViewHook {

    fun hook(classLoader: ClassLoader) {
        val cls = try {
            classLoader.loadClass("android.webkit.WebViewClient")
        } catch (e: Throwable) {
            Logger.e("WebViewClient 类未找到", e)
            return
        }

        // onReceivedSslError → 自动 proceed
        try {
            val m = cls.getDeclaredMethod(
                "onReceivedSslError",
                WebView::class.java, SslErrorHandler::class.java, SslError::class.java,
            )
            XCompat.hookMethod(m, before = { args, _ ->
                if (!HookPrefs.isHookActive("webview")) return@hookMethod null
                (args[1] as SslErrorHandler).proceed()
                Logger.d("WebViewClient.onReceivedSslError → 已自动 proceed")
                null
            })
        } catch (e: Throwable) {
            Logger.e("WebView onReceivedSslError Hook 失败", e)
        }

        // onReceivedError → DO_NOTHING
        try {
            val m = cls.getDeclaredMethod(
                "onReceivedError",
                WebView::class.java, Int::class.javaPrimitiveType,
                String::class.java, String::class.java,
            )
            XCompat.hookMethod(m, before = { _, _ ->
                if (!HookPrefs.isHookActive("webview")) return@hookMethod null
                null
            })
        } catch (e: Throwable) {
            Logger.e("WebView onReceivedError Hook 失败", e)
        }
    }
}
