package com.liquidlyre.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.liquidlyre.app.R
import com.liquidlyre.app.glass.LiquidBottomTab
import com.liquidlyre.app.glass.LiquidBottomTabs
import com.liquidlyre.app.vm.MainViewModel

/** 由四个滑杆实时构造玻璃参数（去折痕 clamp 在 liquidGlass 内部保证） */
fun glassParamsFor(blur: Float, height: Float, amount: Float, chroma: Float) =
    GlassParams(blur, height, amount, chroma)

@Composable
fun LyreAppScreen() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(context))
    val ui by vm.ui.collectAsState()
    val player by vm.player.collectAsState()
    val glass = glassParamsFor(ui.glassBlur, ui.glassHeight, ui.glassAmount, ui.glassChroma)

    val backdrop = rememberLayerBackdrop()
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addTreeUri(uri)
    }
    val wallpaperLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importWallpaper(uri)
    }

    // 自定义底图：文件真相同步决定画哪张图；bgRevision 变化时重新解码 + alpha 淡入（底图非玻璃，淡入安全）
    val bgRevision by vm.bgRevision.collectAsState()
    val defaultBmp = remember { android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_default) }
    val wallpaperBmp = remember(bgRevision) {
        com.liquidlyre.app.util.WallpaperStore.loadBitmap(context) ?: defaultBmp
    }
    val wallpaperImage = remember(bgRevision) { wallpaperBmp.asImageBitmap() }
    val bgAlpha = remember { Animatable(1f) }
    LaunchedEffect(bgRevision) {
        if (bgRevision > 0) { bgAlpha.snapTo(0f); bgAlpha.animateTo(1f, tween(280)) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0C12))) {
        // 可折射底图（进 backdrop，玻璃才有东西可采）
        Image(
            bitmap = wallpaperImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(bgAlpha.value).layerBackdrop(backdrop),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.28f)))

        // 页面内容区（底部留出 Dock 高度）
        Box(Modifier.fillMaxSize().padding(bottom = 84.dp)) {
            when (ui.tab) {
                0 -> LibraryTab(vm, backdrop, glass, onAddDir = { treeLauncher.launch(null) })
                1 -> StageTab(vm, backdrop, glass)
                2 -> SettingsTab(vm, backdrop, glass,
                    onEnableA11y = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onPickWallpaper = { wallpaperLauncher.launch(arrayOf("image/*")) })
            }
        }

        // 底部 Dock：fillMaxWidth + BottomCenter（绝不 fillMaxSize）
        LiquidBottomTabs(
            selectedTabIndex = { ui.tab },
            onTabSelected = { vm.setTab(it) },
            backdrop = backdrop,
            tabsCount = 3,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .padding(horizontal = 20f.dp, vertical = 14f.dp)
        ) {
            LiquidBottomTab(onClick = { vm.setTab(0) }) {
                BasicText("♬", style = TextStyle(Color.White, 20f.sp))
                BasicText("曲库", style = TextStyle(Color.White, 11f.sp))
            }
            LiquidBottomTab(onClick = { vm.setTab(1) }) {
                BasicText("🎹", style = TextStyle(Color.White, 18f.sp))
                BasicText("演奏台", style = TextStyle(Color.White, 11f.sp))
            }
            LiquidBottomTab(onClick = { vm.setTab(2) }) {
                BasicText("⚙", style = TextStyle(Color.White, 19f.sp))
                BasicText("设置", style = TextStyle(Color.White, 11f.sp))
            }
        }

        // 首次免责声明（内嵌玻璃对话框）
        GlassDialog(
            visible = !ui.disclaimer,
            onDismiss = { vm.acceptDisclaimer() },
            backdrop = backdrop,
            glass = glass,
            title = "免责声明",
            confirmText = "我已了解并同意"
        ) {
            BasicText(
                "本工具仅供个人学习研究，仅模拟触摸输入，不读取或修改游戏数据、不绕过反作弊。\n\n" +
                    "第三方脚本可能违反游戏用户协议，封号风险由用户自行承担。请仅在你自己的设备上、自己打开琴界面后手动使用。",
                style = TextStyle(Color.White.copy(0.9f), 13f.sp, lineHeight = 20f.sp)
            )
        }

        // 提示 Snackbar（初值 null）
        GlassSnackbar(
            message = player.message,
            resetKey = player.messageKey,
            backdrop = backdrop,
            glass = glass,
            onDismiss = { vm.clearToast() }
        )
    }
}
