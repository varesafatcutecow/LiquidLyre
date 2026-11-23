package com.liquidlyre.app.ui

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import com.liquidlyre.app.service.LyreAccessibilityService

/** 无障碍服务是否真正开启（系统设置里检查组件是否 enabled） */
fun isA11yEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${LyreAccessibilityService::class.java.name}"
    val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    // 系统 enabled 列表分隔符因厂商/版本不同可能是 ':' 或 ','，两者都要兼容
    return raw.split(':', ',').any { it.trim().equals(expected, ignoreCase = true) }
}

/** 无障碍服务进程是否在运行（交叉验证） */
fun isServiceRunning(context: Context): Boolean = try {
    val am = context.getSystemService(ActivityManager::class.java)
    am?.getRunningServices(Int.MAX_VALUE)?.any {
        it.service.className == LyreAccessibilityService::class.java.name
    } ?: false
} catch (_: Throwable) {
    false
}
