package com.liquidlyre.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidlyre.app.glass.LiquidSlider
import com.liquidlyre.app.glass.LiquidToggle
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay

/**
 * v1.1 液态玻璃组件库：把 AndroidLiquidGlass 的 vibrancy → blur → lens 折射链
 * 复用到 8 类常见控件上。所有组件都走 [liquidGlass]，共享同一组满配参数，
 * 因此折射强度、色散、高光与 Dock / 放大镜完全一致 —— 视觉上是「一整块液态玻璃」。
 *
 * 为什么不用 Material 组件：Material 的 Surface / Card 会画自己的不透明底色，
 * 把下层像素盖住，AGSL 折射就看不到底图，玻璃会变成磨砂塑料。
 */

private val widgetShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(1f, 1f), 3f)
private val widgetInputStyle = TextStyle(Color.White, 14f.sp, shadow = widgetShadow)

private fun Modifier.glassClick(onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, onClick = onClick)

// ══════════════════════════════════════════════════════════ 1) 悬浮按钮 FAB

/**
 * 液态玻璃 FAB。整颗按钮就是一块胶囊玻璃（含边缘折射 + 色散 + 高光），
 * 内部不画任何纯色底板，拖到绚丽底图上能看到明显的放大镜式形变。
 */
@Composable
fun GlassFab(
    onClick: () -> Unit,
    backdrop: Backdrop,
    glass: GlassParams,
    modifier: Modifier = Modifier,
    label: String = "＋"
) {
    Box(
        modifier
            .size(60f.dp)
            .liquidGlass(
                backdrop, glass,
                shape = { Capsule() },
                extraSurface = { drawRect(Color.White.copy(alpha = 0.13f)) }
            )
            .glassClick(onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(Color.White, 26f.sp, shadow = widgetShadow))
    }
}

// ══════════════════════════════════════════════════════════ 2) 底部抽屉 / Dock 扩展

/**
 * 液态玻璃底部抽屉。与官方融合 Dock 并存：Dock 负责三个主 Tab，
 * 抽屉负责「快捷动作」。刻意做成内嵌式（不是独立窗口 Dialog），
 * 这样 layerBackdrop 才能捕获到主界面的像素供 AGSL 折射。
 */
@Composable
fun BoxScope.GlassBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    glass: GlassParams,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // 遮罩：只做很淡的压暗，保证下层底图仍能被玻璃折射出来
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .glassClick(onDismiss)
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).then(modifier),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(
                    backdrop, glass,
                    shape = { RoundedRectangle(32f.dp) },
                    cornerRadius = 32f,
                    extraSurface = { drawRect(Color.Black.copy(alpha = 0.12f)) }
                )
                .padding(horizontal = 20f.dp, vertical = 16f.dp),
            verticalArrangement = Arrangement.spacedBy(10f.dp)
        ) {
            // 抽屉把手
            Box(
                Modifier
                    .width(42f.dp)
                    .height(4f.dp)
                    .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2f.dp))
                    .align(Alignment.CenterHorizontally)
            )
            BasicText(title, style = glassTitleStyle)
            content()
        }
    }
}

// ══════════════════════════════════════════════════════════ 3) 对话框

/**
 * 液态玻璃对话框。同样内嵌在主 Compose 树中（不用 ui.window.Dialog），
 * 原因是独立窗口没有下层底图，玻璃会退化成一张普通半透明卡片。
 */
@Composable
fun GlassDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    glass: GlassParams,
    title: String,
    confirmText: String = "知道了",
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .glassClick(onDismiss)
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26f.dp)
                    .liquidGlass(
                        backdrop, glass,
                        shape = { RoundedRectangle(32f.dp) },
                        cornerRadius = 32f,
                        extraSurface = { drawRect(Color.Black.copy(alpha = 0.12f)) }
                    )
                    .padding(22f.dp),
                verticalArrangement = Arrangement.spacedBy(12f.dp)
            ) {
                BasicText(title, style = glassTitleStyle)
                content()
                com.liquidlyre.app.glass.LiquidButton(
                    onClick = onDismiss,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicText(confirmText, style = glassBodyStyle)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════ 4) 搜索栏

/**
 * 胶囊玻璃搜索栏：左侧放大镜、中间 BasicTextField、右侧一键清除。
 * 不用 Material SearchBar，避免它自带的 opaque 背景破坏折射。
 */
@Composable
fun GlassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    backdrop: Backdrop,
    glass: GlassParams,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .liquidGlass(
                backdrop, glass,
                shape = { Capsule() },
                extraSurface = { drawRect(Color.Black.copy(alpha = 0.16f)) }
            )
            .padding(horizontal = 16f.dp, vertical = 12f.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10f.dp)
    ) {
        BasicText("⌕", style = glassSectionStyle)
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = widgetInputStyle,
                singleLine = true,
                cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    if (value.isEmpty()) BasicText(hint, style = glassSmallStyle)
                    inner()
                }
            )
        }
        if (value.isNotEmpty()) {
            BasicText(
                "✕",
                style = glassBodyStyle,
                modifier = Modifier.glassClick { onValueChange("") }
            )
        }
    }
}

// ══════════════════════════════════════════════════════════ 5) 玻璃卡片 / 列表

/** 一张玻璃卡里放多行设置项，行间用极淡分隔线，整体仍是一整块可折射的玻璃 */
@Composable
fun GlassListCard(
    backdrop: Backdrop,
    glass: GlassParams,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(backdrop, glass, modifier, title, content)
}

@Composable
fun GlassListRow(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.glassClick { onClick.invoke() }
                else Modifier
            )
            .padding(vertical = 8f.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12f.dp)
    ) {
        BasicText(icon, style = glassSectionStyle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3f.dp)) {
            BasicText(title, style = glassBodyStyle)
            if (subtitle != null) GlassSmall(subtitle)
        }
        if (trailing != null) BasicText(trailing, style = glassSmallStyle)
    }
}

@Composable
fun GlassDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1f.dp)
            .background(Color.White.copy(alpha = 0.14f))
    )
}

// ══════════════════════════════════════════════════════════ 6) 分段控件 Segmented

/**
 * 液态玻璃分段控件。外层是一整块胶囊玻璃轨道，选中项是一块「会滑动的小玻璃」：
 * 用 spring 动画驱动 translationX， Indicator 自身也走 liquidGlass，
 * 所以滑动过程中边缘折射和色散会跟着移动，像一滴水在玻璃管里跑。
 */
@Composable
fun GlassSegmented(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    glass: GlassParams,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(50f.dp)
    ) {
        val segmentWidth = maxWidth / items.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f, visibilityThreshold = 1f.dp),
            label = "segmentedIndicator"
        )

        // 轨道
        Box(
            Modifier
                .fillMaxSize()
                .liquidGlass(
                    backdrop, glass,
                    shape = { Capsule() },
                    extraSurface = { drawRect(Color.Black.copy(alpha = 0.16f)) }
                )
        )

        // 滑动的选中玻璃
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .padding(4f.dp)
                .width(segmentWidth - 8f.dp)
                .fillMaxHeight()
                .liquidGlass(
                    backdrop, glass,
                    shape = { Capsule() },
                    extraSurface = { drawRect(Color.White.copy(alpha = 0.20f)) }
                )
        )

        // 文字与点击热区
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .glassClick { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        label,
                        style = if (index == selectedIndex) glassSectionStyle else glassLabelStyle
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════ 7) Snackbar / 底部提示条

/**
 * 液态玻璃提示条：从 Dock 上方滑入，3.2 秒自动消失，可带一个动作。
 * 替换系统 Toast，让所有反馈也保持同一套玻璃质感。
 */
@Composable
fun BoxScope.GlassSnackbar(
    message: String?,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    glass: GlassParams,
    modifier: Modifier = Modifier,
    // 连续两次弹出相同文案时，用递增 key 强制重新计时，避免计时不刷新
    resetKey: Int = 0,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    LaunchedEffect(message, resetKey) {
        if (message != null) {
            delay(3200)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Row(
            Modifier
                .liquidGlass(
                    backdrop, glass,
                    shape = { Capsule() },
                    extraSurface = { drawRect(Color.Black.copy(alpha = 0.20f)) }
                )
                .padding(horizontal = 18f.dp, vertical = 14f.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14f.dp)
        ) {
            BasicText(
                message ?: "",
                style = glassBodyStyle,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (actionLabel != null && onAction != null) {
                BasicText(
                    actionLabel,
                    style = glassSectionStyle,
                    modifier = Modifier.glassClick {
                        onAction.invoke()
                        onDismiss()
                    }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════ 8) 带标签的开关 / 滑块

/** 官方 LiquidToggle 外面补一层标签与说明，作为设置项行使用 */
@Composable
fun GlassSettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3f.dp)) {
            GlassBody(label)
            if (subtitle != null) GlassSmall(subtitle)
        }
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = backdrop
        )
    }
}

/** 官方 LiquidSlider 外面补一层标签与实时数值，作为设置项行使用 */
@Composable
fun GlassSettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    backdrop: Backdrop,
    onValueChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6f.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GlassLabel(label)
            BasicText(formatFloat(value), style = glassBodyStyle)
        }
        LiquidSlider(
            value = { value },
            onValueChange = onValueChange,
            valueRange = valueRange,
            visibilityThreshold = 0.001f,
            backdrop = backdrop,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}
