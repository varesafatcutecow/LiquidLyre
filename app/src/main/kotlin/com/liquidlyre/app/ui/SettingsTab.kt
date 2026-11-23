package com.liquidlyre.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.kyant.backdrop.Backdrop
import com.liquidlyre.app.engine.PlayerState
import com.liquidlyre.app.glass.LiquidButton
import com.liquidlyre.app.service.LyreAccessibilityService
import com.liquidlyre.app.vm.MainViewModel

@Composable
fun SettingsTab(
    vm: MainViewModel,
    backdrop: Backdrop,
    glass: GlassParams,
    onEnableA11y: () -> Unit,
    onPickWallpaper: () -> Unit
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val player by vm.player.collectAsState()
    val a11yOk = isA11yEnabled(context)
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addTreeUri(uri)
    }

    // 固定 9 张卡，用普通 Column + verticalScroll 而非 LazyColumn：
    // LazyColumn 快速滑动会反复创建/销毁列表项，每张卡的 AGSL 玻璃 backdrop
    // 随之反复分配释放，是「快速划到底闪退」的高发点。固定项不需要懒加载。
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16f.dp, vertical = 14f.dp),
        verticalArrangement = Arrangement.spacedBy(14f.dp)
    ) {
        BasicText("设置", style = glassTitleStyle)

        // 无障碍授权引导
        GlassListCard(backdrop, glass, title = "① 无障碍服务（必需）") {
            GlassListRow("🔓", if (a11yOk) "已开启" else "未开启 · 点此开启",
                subtitle = "用于坐标手势与前台检测，随用随开") { onEnableA11y() }
            GlassListRow("📢", "通知权限", subtitle = "保活通知常驻，显示当前曲目") {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    })
                }
            }
        }

        // 目标包名
        GlassListCard(backdrop, glass, title = "② 目标游戏包名") {
            GlassTextField(
                value = ui.targetPackage,
                onValueChange = { vm.targetPkg(it) },
                hint = "com.miHoYo.Yuanshen",
                backdrop = backdrop,
                params = glass
            )
            GlassSmall("国服 com.miHoYo.Yuanshen · 国际服 com.miHoYo.GenshinImpact，可手填")
        }

        // 曲谱目录管理
        GlassListCard(backdrop, glass, title = "③ 曲谱目录（多目录增删排序）") {
            LiquidButton(onClick = { treeLauncher.launch(null) }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                BasicText("＋ 添加目录", style = glassBodyStyle)
            }
            if (ui.treeDirs.isEmpty()) {
                GlassSmall("选择存放 .json 谱的文件夹。支持谐律键琴 MIDI（type=midi，notes 形如 DQ72|UQ168）与 BGI 文本键盘谱；选中 BGI 根目录时会自动进 score_file/。")
            } else {
                ui.treeDirs.forEach { dir ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            (if (dir.valid) "📁 " else "⚠️ ") + dir.name,
                            style = TextStyle(if (dir.valid) Color.White else Color(0xFFFF6B6B), 13f.sp)
                        )
                        Box(Modifier.weight(1f))
                        if (!dir.valid) {
                            LiquidButton(onClick = { treeLauncher.launch(null) }, backdrop = backdrop) {
                                BasicText("重新授权", style = glassSmallStyle)
                            }
                        }
                        LiquidButton(onClick = { vm.pinTree(dir.uri) }, backdrop = backdrop) {
                            BasicText("置顶", style = glassSmallStyle)
                        }
                        LiquidButton(onClick = { vm.removeTreeUri(dir.uri) }, backdrop = backdrop) {
                            BasicText("删除", style = glassSmallStyle)
                        }
                    }
                }
            }
            LiquidButton(onClick = { vm.scanLibrary() }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                BasicText("↻ 刷新曲库", style = glassSmallStyle)
            }
        }

        // 液态玻璃四滑杆
        GlassListCard(backdrop, glass, title = "④ 液态玻璃（四滑杆实时调）") {
            GlassSettingSlider("模糊半径", ui.glassBlur, 0f..32f, { vm.glassBlurLive(it) }, backdrop, onValueChangeFinished = { vm.commitGlassBlur() })
            GlassSettingSlider("折射高度", ui.glassHeight, 0f..1f, { vm.glassHeightLive(it) }, backdrop, onValueChangeFinished = { vm.commitGlassHeight() })
            GlassSettingSlider("折射强度", ui.glassAmount, 0f..1f, { vm.glassAmountLive(it) }, backdrop, onValueChangeFinished = { vm.commitGlassAmount() })
            GlassSettingSlider("色散(0关/1开)", ui.glassChroma, 0f..1f, { vm.glassChromaLive(it) }, backdrop, onValueChangeFinished = { vm.commitGlassChroma() })
            GlassSmall("满配=四滑杆全上界(32/1/1/1)。折射高度按真实圆角 clamp，任意组合都不折痕。设置自动记忆。")
        }

        // 自定义底图
        GlassListCard(backdrop, glass, title = "⑤ 自定义底图（玻璃折射来源）") {
            val bgRev by vm.bgRevision.collectAsState()
            val hasCustom = remember(bgRev) { com.liquidlyre.app.util.WallpaperStore.hasCustom(context) }
            LiquidButton(onClick = onPickWallpaper, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                BasicText("🖼 选择本地图片作为底图", style = glassBodyStyle)
            }
            if (hasCustom) {
                LiquidButton(onClick = { vm.resetWallpaper() }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    BasicText("↺ 恢复默认底图", style = glassSmallStyle)
                }
            }
            GlassSmall(if (hasCustom) "当前：自定义底图（已复制到本地，重启有效）" else "当前：内置渐变底图。底图决定玻璃折射出的色彩，建议高对比大图。")
        }

        // 性能与手感
        GlassListCard(backdrop, glass, title = "⑥ 性能与手感") {
            GlassSettingSwitch("悬浮窗性能模式", ui.perfMode, { vm.setPerf(it) }, backdrop,
                subtitle = "游戏前台负载高时关色散、降折射")
            GlassSettingSwitch("缓存高速模式", ui.cacheFast, { vm.setCacheFast(it) }, backdrop,
                subtitle = "直接读 BGI cache/ 预处理谱，跳过解析")
            GlassSettingSlider("速度补偿", ui.speedComp, 0.6f..1.4f, { vm.speedLive(it) }, backdrop, onValueChangeFinished = { vm.commitSpeed() })
            GlassSettingSlider("和弦展宽(ms)", ui.chordSpread.toFloat(), 1f..15f, { vm.chordSpreadLive(it.roundToInt()) }, backdrop, onValueChangeFinished = { vm.commitChordSpread() })
            GlassSettingSlider("悬浮窗模糊", ui.overlayBlur, 8f..32f, { vm.overlayBlurLive(it) }, backdrop, onValueChangeFinished = { vm.commitOverlayBlur() })
        }

        // 悬浮窗几何
        GlassListCard(backdrop, glass, title = "⑦ 悬浮窗尺寸与位置") {
            GlassSettingSwitch("显示悬浮窗", ui.overlayEnabled, { vm.setOverlayEnabled(it) }, backdrop,
                subtitle = "关闭后无障碍服务仍在，但游戏内不显示悬浮窗")
            GlassSmall("宽高都会生效；比例过扁会自动收敛，保证三个按钮不被裁切。拖动窗口后位置自动记忆。")
            GlassSettingSlider("宽度", ui.overlayW.toFloat(), 500f..2000f, { vm.overlaySizeLive(it.roundToInt(), ui.overlayH) }, backdrop, onValueChangeFinished = { vm.commitOverlaySize() })
            GlassSettingSlider("高度", ui.overlayH.toFloat(), 300f..1000f, { vm.overlaySizeLive(ui.overlayW, it.roundToInt()) }, backdrop, onValueChangeFinished = { vm.commitOverlaySize() })
            GlassSettingSlider("X", ui.overlayX.toFloat(), 0f..3200f, { vm.overlayPosLive(it.roundToInt(), ui.overlayY) }, backdrop, onValueChangeFinished = { vm.commitOverlayPos() })
            GlassSettingSlider("Y", ui.overlayY.toFloat(), 0f..1500f, { vm.overlayPosLive(ui.overlayX, it.roundToInt()) }, backdrop, onValueChangeFinished = { vm.commitOverlayPos() })
            GlassSettingSwitch("允许拖拽（默认关，防误触）", ui.overlayDraggable, { vm.setOverlayDraggable(it) }, backdrop,
                subtitle = "折叠成点后始终可拖动，不受此开关影响")
        }

        // 琴键校准
        GlassListCard(backdrop, glass, title = "⑧ 琴键校准（决定弹得准不准）") {
            LiquidButton(onClick = {
                if (!a11yOk) {
                    PlayerState.toast("请先在 ① 开启无障碍服务")
                } else {
                    context.sendBroadcast(
                        Intent(LyreAccessibilityService.ACTION_CALIBRATE)
                            .setPackage(context.packageName)
                    )
                    PlayerState.toast("已进入校准模式，请切回游戏调整")
                }
            }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                BasicText("🎯 打开全屏校准（拖 21 个点到琴键）", style = glassBodyStyle)
            }
            LiquidButton(onClick = {
                if (!a11yOk) {
                    PlayerState.toast("请先在 ① 开启无障碍服务")
                } else {
                    context.sendBroadcast(
                        Intent(LyreAccessibilityService.ACTION_TEST_TAP)
                            .setPackage(context.packageName)
                    )
                }
            }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                BasicText("👆 试弹 Q 键（验证坐标与注入）", style = glassSmallStyle)
            }
            GlassSmall(
                "点开后切回游戏：21 个圆点会盖在游戏画面上，逐个拖到对应琴键，" +
                    "点顶部「保存」。默认坐标按 3168×1440 推算，换设备必须校准。" +
                    "「试弹 Q 键」请在游戏内使用：看琴弦是否被拨动，验证注入链路通不通。"
            )
        }

        // 诊断日志 + 崩溃堆栈
        GlassListCard(backdrop, glass, title = "⑨ 诊断日志") {
            val crash = PlayerState.lastCrash
            if (crash != null) {
                BasicText("⚠️ 上次崩溃堆栈（截图发开发者）：", style = TextStyle(Color(0xFFFF9B9B), 12f.sp))
                BasicText(crash, style = TextStyle(Color.White.copy(0.9f), 10f.sp, lineHeight = 14f.sp))
                LiquidButton(onClick = { PlayerState.clearCrash(context) }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    BasicText("清除崩溃记录", style = glassSmallStyle)
                }
            }
            val diagText = if (player.diag.isEmpty()) "暂无运行日志" else player.diag.take(12).joinToString("\n")
            BasicText(diagText, style = TextStyle(Color.White.copy(0.85f), 11f.sp, lineHeight = 16f.sp))
        }

        GlassSmall("璃音 LiquidLyre v1.6.5 · 仅供个人学习研究 · 封号风险自负")
    }
}
