package com.liquidlyre.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/**
 * 全局玻璃参数。默认值即官方 GlassPlayground 四个滑杆全部拉到上界 —— 单块玻璃满配：
 *   blurRadiusDp = 32、refractionHeightFrac = 1、refractionAmountFrac = 1、chromaticAberration = 1。
 * 所有界面玻璃（卡片、按钮、Dock、放大镜）共用这组参数，保证「完全液态透亮」。
 */
data class GlassParams(
    val blurDp: Float,
    val heightFrac: Float,
    val amountFrac: Float,
    val chroma: Float
)

private val textShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(1f, 1f), 3f)

val glassTitleStyle = TextStyle(Color.White, 24f.sp, FontWeight.Bold, shadow = textShadow)
val glassSectionStyle = TextStyle(Color.White, 16f.sp, FontWeight.Medium, shadow = textShadow)
val glassLabelStyle = TextStyle(Color.White.copy(alpha = 0.85f), 12f.sp, shadow = textShadow)
val glassBodyStyle = TextStyle(Color.White, 13f.sp, shadow = textShadow)
val glassSmallStyle = TextStyle(Color.White.copy(alpha = 0.82f), 11f.sp, shadow = textShadow)

/**
 * 通用液态玻璃 Modifier：与官方 GlassPlaygroundContent 的效果链一致，
 * vibrancy 提饱和 → blur 打散 → lens 按 AGSL SDF 做边缘折射 + 色散，最后一层高光。
 * 表面只画 7% 白，保持「完全液态透亮」而不是磨砂卡片。
 *
 * 【去折痕 · v1.2 真修复】旧版 refractionHeight = heightFrac * minDimension * 0.5，
 * 满配时高达最小边的 1/2，远超 shape 圆角半径 → SDF 四角采样沿对角线折叠 → X 形折痕。
 * 真修法按官方约定：折射高度上界 = 该形状实际圆角半径 cornerRadius(dp)，
 *   且 cornerRadiusPx ≤ minDimension*0.5（胶囊半径上限）；
 *   refractionHeight ∈ [0, cornerRadiusPx]，refractionAmount ∈ [0, minDimension]。
 * 调用方按自身 shape 传 cornerRadius：28dp 卡传 28、32dp 传 32、16dp 传 16、100dp 传 100。
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    params: GlassParams,
    shape: () -> Shape = { RoundedRectangle(28f.dp) },
    /** 该形状的实际圆角半径（dp）；lens 折射高度严格 clamp 到该值内，消除方块卡片折痕 */
    cornerRadius: Float = 28f,
    extraSurface: (DrawScope.() -> Unit)? = null
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        val minDimension = size.minDimension
        // 关键：上界用「实际圆角半径」而非 minDimension*0.5；再 coerceAtMost 兜住胶囊半径
        val cornerRadiusPx = cornerRadius.dp.toPx().coerceAtMost(minDimension * 0.5f)
        vibrancy()
        blur(params.blurDp.dp.toPx())
        lens(
            refractionHeight = (params.heightFrac * cornerRadiusPx).coerceIn(0f, cornerRadiusPx),
            refractionAmount = (params.amountFrac * minDimension).coerceIn(0f, minDimension),
            depthEffect = true,
            chromaticAberration = params.chroma > 0f
        )
    },
    highlight = { Highlight.Default },
    onDrawSurface = {
        drawRect(Color.White.copy(alpha = 0.07f))
        extraSurface?.invoke(this)
    }
)

@Composable
fun GlassCard(
    backdrop: Backdrop,
    params: GlassParams,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .liquidGlass(backdrop, params)
            .padding(18f.dp),
        verticalArrangement = Arrangement.spacedBy(12f.dp)
    ) {
        if (title != null) BasicText(title, style = glassSectionStyle)
        content()
    }
}

@Composable
fun GlassLabel(text: String) {
    BasicText(text, style = glassLabelStyle)
}

@Composable
fun GlassBody(text: String) {
    BasicText(text, style = glassBodyStyle)
}

@Composable
fun GlassSmall(text: String) {
    BasicText(text, style = glassSmallStyle)
}

/** 玻璃输入框：BasicTextField + 极淡表面，避免引入 Material 组件破坏整体质感 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    backdrop: Backdrop,
    params: GlassParams,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .liquidGlass(
                backdrop, params,
                shape = { RoundedRectangle(16f.dp) },
                cornerRadius = 16f,
                extraSurface = { drawRect(Color.Black.copy(alpha = 0.14f)) }
            )
            .padding(horizontal = 14f.dp, vertical = 12f.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(Color.White, 14f.sp, shadow = textShadow),
            singleLine = true,
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                if (value.isEmpty()) BasicText(hint, style = glassSmallStyle)
                inner()
            }
        )
    }
}

@Composable
fun GlassKeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        GlassLabel(label)
        BasicText(value, style = glassBodyStyle)
    }
}

fun formatFloat(v: Float): String = when {
    v >= 10f -> "%.0f".format(v)
    else -> "%.2f".format(v)
}
