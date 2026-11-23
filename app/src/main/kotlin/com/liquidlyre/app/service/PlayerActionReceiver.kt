package com.liquidlyre.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 悬浮窗控件广播的接收器，动作回调给无障碍服务处理 */
class PlayerActionReceiver(private val onAction: (String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.action?.let { onAction(it) }
    }
}
