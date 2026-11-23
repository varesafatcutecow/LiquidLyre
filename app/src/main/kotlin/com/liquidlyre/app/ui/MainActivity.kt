package com.liquidlyre.app.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.liquidlyre.app.engine.PlayerState

/** 单 Activity + Compose Navigation（用状态切换页签） */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ensureNotificationPermission()
        setContent { LyreAppScreen() }
        // 启动即弹上次崩溃堆栈：用系统原生 AlertDialog，不依赖 Compose/玻璃渲染，
        // 即使「一进设置页就崩」也能稳定显示，用户截图即可反馈（无需 adb）。
        showCrashIfAny()
    }

    private fun showCrashIfAny() {
        PlayerState.reloadCrash(this)
        val crash = PlayerState.lastCrash ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚠️ 上次崩溃堆栈 · 请截图发开发者")
            .setMessage(crash)
            .setPositiveButton("复制堆栈") { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("lyre_crash", crash))
                } catch (_: Throwable) {}
            }
            .setNeutralButton("清除记录") { _, _ -> PlayerState.clearCrash(this) }
            .setNegativeButton("关闭", null)
            .setCancelable(false)
            .create()
        // 等窗口就绪再弹，避免 onCreate 阶段 token 未就绪
        window.decorView.post { if (!isFinishing && !isDestroyed) dialog.show() }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }
}
