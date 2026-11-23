package com.liquidlyre.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.liquidlyre.app.R
import com.liquidlyre.app.engine.PlayerState
import com.liquidlyre.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台服务保活：通知栏常驻显示当前曲目/状态。不伪装媒体会话，仅普通 specialUse 通知。
 * 用户开无障碍服务时由主 App 一并启动；服务被系统杀掉会由无障碍服务在 onServiceConnected 时拉起。
 */
class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL = "lyre_keepalive"
        const val NOTIF_ID = 1001
        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("璃音待命"))
        scope.launch {
            PlayerState.snap.collectLatest { s ->
                val text = when (s.state) {
                    com.liquidlyre.app.engine.PlayState.PLAYING -> "演奏中 · ${s.current?.name ?: ""}"
                    com.liquidlyre.app.engine.PlayState.PAUSED -> "已暂停 · ${s.current?.name ?: ""}"
                    com.liquidlyre.app.engine.PlayState.STOPPED -> "已急停"
                    com.liquidlyre.app.engine.PlayState.ERROR -> "错误"
                    com.liquidlyre.app.engine.PlayState.IDLE -> "璃音待命"
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "璃音保活", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "显示当前曲目与演奏状态"
                        setSound(null, null)
                    }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("璃音 · LiquidLyre")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
