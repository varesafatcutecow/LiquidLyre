package com.liquidlyre.app.ui

import android.graphics.PointF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.liquidlyre.app.data.KeyMap
import com.liquidlyre.app.engine.PlayState
import com.liquidlyre.app.glass.LiquidButton
import com.liquidlyre.app.vm.MainViewModel

@Composable
fun StageTab(vm: MainViewModel, backdrop: Backdrop, glass: GlassParams) {
    val context = LocalContext.current
    val player by vm.player.collectAsState()
    val ui by vm.ui.collectAsState()
    val overrides by vm.prefs.overrides.collectAsState(initial = emptyMap())
    // 无障碍状态灯实时读系统设置（服务 onCreate 才置内存位，冷启动会误报"未开启"）
    val a11yOn = isA11yEnabled(context) || player.a11yOn

    Column(Modifier.fillMaxSize().padding(horizontal = 16f.dp, vertical = 14f.dp)) {
        BasicText("演奏台", style = glassTitleStyle)
        Spacer(Modifier.height(12f.dp))

        // 状态灯行
        GlassListCard(backdrop, glass) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusPill("无障碍", a11yOn)
                StatusPill("游戏前台", player.gameForeground)
                StatusPill(
                    when (player.state) {
                        PlayState.PLAYING -> "演奏中"
                        PlayState.PAUSED -> "已暂停"
                        PlayState.STOPPED -> "已急停"
                        PlayState.ERROR -> "错误"
                        else -> "待命"
                    },
                    player.state == PlayState.PLAYING
                )
            }
            BasicText("曲目：${player.current?.name ?: "未选（去曲库点选）"}", style = glassBodyStyle)
            BasicText(
                player.parsed?.let {
                    "BPM ${it.bpm} · ${MainViewModel.formatSource(it)} · ${MainViewModel.mmss(it.durationMs)}"
                } ?: "解析结果：--",
                style = glassSmallStyle
            )
        }
        Spacer(Modifier.height(12f.dp))

        // 3×7 玻璃琴键网格，弹到哪个哪个高亮
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f.dp)) {
            KeyMap.ALL_ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { key ->
                        GlassKey(key, lit = key in player.currentKeys, backdrop, glass)
                    }
                }
            }
        }
        Spacer(Modifier.height(8f.dp))

        // 演奏台 = 试听，永远出声。这个开关只控制悬浮窗真实弹奏时是否也出声。
        GlassSettingSwitch(
            "🔊 悬浮窗演奏时也出声",
            ui.simulateAudio,
            { vm.setSimulateAudio(it) },
            backdrop,
            subtitle = if (ui.simulateAudio) "游戏里真实弹奏 + 手机本地钢琴音同时响" else "只在游戏里真实弹奏，手机不发声"
        )
        Spacer(Modifier.height(8f.dp))
        // 自检：绕过播放引擎直接发一个 A 音。有声=播放链路问题，无声=音频设备/音量问题。
        LiquidButton(onClick = { vm.testTone() }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            BasicText("🔊 测试音（验证手机能否发声）", style = glassSmallStyle)
        }
        Spacer(Modifier.height(12f.dp))

        // 大号播放 / 急停
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12f.dp)) {
            LiquidButton(onClick = { vm.play() }, backdrop = backdrop, modifier = Modifier.weight(1.4f)) {
                BasicText(if (player.state == PlayState.PLAYING) "▶ 演示中" else "▶ 试听演示", style = glassBodyStyle)
            }
            LiquidButton(onClick = { vm.pause() }, backdrop = backdrop, modifier = Modifier.weight(1f)) {
                BasicText("⏸ 暂停", style = glassBodyStyle)
            }
            LiquidButton(onClick = { vm.stop() }, backdrop = backdrop, modifier = Modifier.weight(1f)) {
                BasicText("⏹ 急停", style = glassBodyStyle)
            }
        }
        GlassSmall("试听只发声、不碰游戏。要真正弹进游戏，请在游戏里用悬浮窗 ▶。")
        Spacer(Modifier.height(12f.dp))
        LiquidButton(onClick = { vm.setTab(2) }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            BasicText("🎯 琴键校准（在设置页）", style = glassSmallStyle)
        }
    }
}

@Composable
private fun StatusPill(label: String, on: Boolean) {
    BasicText(
        "● $label",
        style = TextStyle(if (on) Color(0xFF7CFF9B) else Color(0xFFAAAAAA), 12f.sp, FontWeight.Medium)
    )
}

@Composable
private fun GlassKey(key: String, lit: Boolean, backdrop: Backdrop, glass: GlassParams) {
    val scale by animateFloatAsState(if (lit) 1.18f else 1f, label = "key$key")
    Box(
        Modifier
            .size(46f.dp)
            .scale(scale)
            .liquidGlass(
                backdrop,
                if (lit) GlassParams(20f, 1f, 1f, 1f) else glass,
                shape = { Capsule() },
                cornerRadius = 23f,
                extraSurface = {
                    drawRect(if (lit) Color(0xFFFF6FA8).copy(0.55f) else Color.White.copy(0.08f))
                }
            ),
        Alignment.Center
    ) {
        BasicText(key, style = TextStyle(Color.White, 15f.sp, FontWeight.Bold))
    }
}
