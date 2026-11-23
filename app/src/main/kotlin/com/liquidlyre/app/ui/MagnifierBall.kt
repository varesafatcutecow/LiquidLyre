package com.liquidlyre.app.ui

import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.shapes.Capsule

/**
 * 🥇 可拖拽放大镜 / 悬浮检视球 —— 对应官方 demo 的 MagnifierContent。
 *
 * 做法与官方一致：把「底图 backdrop」和「界面内容 backdrop」合并成检视球的输入，
 * 用 lens() 的 AGSL SDF 做边缘折射 + 色散，再用 withTransform 把下层内容放大 1.5 倍。
 * draggable2D 让这颗球可以拖到屏幕任意位置，拖过哪里就放大折射哪里。
 */
@Composable
fun BoxScope.MagnifierBall(backdrop: Backdrop, contentBackdrop: Backdrop) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val combined = rememberCombinedBackdrop(backdrop, contentBackdrop)

    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = 24f.dp, y = 110f.dp)
            .graphicsLayer {
                translationX = offset.x
                translationY = offset.y
            }
            .draggable2D(rememberDraggable2DState { delta -> offset += delta })
            .drawBackdrop(
                backdrop = combined,
                shape = { Capsule() },
                effects = {
                    lens(
                        8f.dp.toPx(),
                        24f.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true
                    )
                },
                innerShadow = { InnerShadow(radius = 16f.dp) },
                onDrawBackdrop = { drawLayerBlock ->
                    withTransform(
                        { scale(1.5f, 1.5f) },
                        drawLayerBlock
                    )
                }
            )
            .size(112f.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText("⌕ 拖动", style = glassSmallStyle)
    }
}
