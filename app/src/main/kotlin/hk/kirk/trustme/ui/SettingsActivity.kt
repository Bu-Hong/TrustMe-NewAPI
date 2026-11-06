package hk.kirk.trustme.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hk.kirk.trustme.ui.theme.TrustMeTheme
import hk.kirk.trustme.xprefs.XposedPrefs

/**
 * 设置界面 — Jetpack Compose 实现
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "trustme_prefs"
    }

    /** 模块激活状态 — 通过 libxposed service 是否绑定判定（Modern API） */
    private var isModuleActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge 纯黑
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT,
            ),
        )

        super.onCreate(savedInstanceState)

        // Modern API：通过 XposedServiceHelper 绑定框架服务，
        // isModuleActive 以 service 是否绑定判定（旧版自 Hook onCreate 的方式
        // 在 API 101+ 已不可能 —— 模块 App 不再被 hook）
        hk.kirk.trustme.xprefs.XposedPrefs.ServiceHolder.register()
        hk.kirk.trustme.xprefs.XposedPrefs.ServiceHolder.addListener { svc ->
            isModuleActive = svc != null
        }

        // 使用 XposedPrefs 桥接 — Remote Preferences + 本地镜像
        val prefs = XposedPrefs.module(this, PREFS_NAME)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        setContent {
            TrustMeTheme {
                SettingsScreen(
                    isModuleActive = isModuleActive,
                    versionName = versionName,
                    prefs = prefs,
                )
            }
        }
    }
}
