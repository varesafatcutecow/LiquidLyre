package com.liquidlyre.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.liquidlyre.app.engine.AudioSynth
import com.liquidlyre.app.engine.PlayerState
import java.io.PrintWriter
import java.io.StringWriter

class LyreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashCatcher()
        AudioSynth.init(this)
        // 保活通知渠道早早建好（无 POST_NOTIFICATIONS 时静默失败即可）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel("lyre_keepalive") == null) {
                nm.createNotificationChannel(
                    NotificationChannel("lyre_keepalive", "璃音保活", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    /**
     * 全局崩溃捕获：把未捕获异常的**完整堆栈**落盘，重开 App 后进设置⑨可直接看到。
     * 这样用户不用连电脑导 logcat，截图即可反馈。
     * 落盘后仍交回系统默认处理（保证该崩还是崩，不掩盖问题）。
     */
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "[${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] " +
                    "线程=${thread.name}\n${sw.toString()}"
                val dir = java.io.File(applicationContext.filesDir, "crash")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, "last_crash.txt").writeText(text.take(12000))
            } catch (_: Throwable) {
            }
            // 交回系统默认 handler（或兜底杀进程），保持原生崩溃行为
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
        }
        // 启动时把上次崩溃读进内存，供设置⑨展示（读完可不清，用户能反复看直到下次崩覆盖）
        PlayerState.reloadCrash(applicationContext)
    }
}
