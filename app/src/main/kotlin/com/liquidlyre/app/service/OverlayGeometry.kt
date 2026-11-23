package com.liquidlyre.app.service

import kotlin.math.roundToInt

/**
 * 悬浮窗几何计算（纯函数，可 JVM 单测）。
 *
 * 从参考图提取比例 1.74:1，但不再把用户设置的高度丢掉：
 * 宽和高都真正参与计算，只做两件事兜底 ——
 *   1. 比例钳制在 [1.15, 2.6]，杜绝横扁条/竖条畸形；
 *   2. 最小宽度保证「左右箭头 + 3 个圆按钮」一定装得下，不再裁切。
 */
object OverlayGeometry {
    const val PANEL_ASPECT = 1.74f          // 参考图宽/高
    const val MIN_ASPECT = 1.15f
    const val MAX_ASPECT = 2.60f

    const val SIDE_BTN_DP = 52f
    const val CENTER_BTN_DP = 84f
    const val BTN_MARGIN_DP = 6f
    const val ARROW_W_DP = 30f
    const val ROW_PADDING_DP = 20f

    /** 按钮行最小需求宽度（dp）：箭头 + 3 圆按钮(含 margin) + 行内边距 */
    fun contentNeedDp(): Float =
        ARROW_W_DP + (SIDE_BTN_DP + BTN_MARGIN_DP * 2) +
            (CENTER_BTN_DP + BTN_MARGIN_DP * 2) +
            (SIDE_BTN_DP + BTN_MARGIN_DP * 2) +
            ARROW_W_DP + ROW_PADDING_DP

    /**
     * 由设置宽高(px) 与当前屏幕(px)、密度推算面板尺寸(px)。
     * 宽高都生效；比例越界时以宽度为基准修正高度。
     */
    fun panelSize(
        widthPx: Int,
        heightPx: Int,
        screenW: Int,
        screenH: Int,
        density: Float
    ): Pair<Int, Int> {
        val needPx = (contentNeedDp() * density).roundToInt() + (10f * density).roundToInt()
        val minW = maxOf(needPx, (300f * density).roundToInt())
        val maxW = maxOf((screenW * 0.94f).toInt(), minW)
        val minH = (170f * density).roundToInt()
        val maxH = maxOf((screenH * 0.85f).toInt(), minH)

        var w = widthPx.coerceIn(minW, maxW)
        // 高度没设过 / 是旧版本默认的 150px → 按参考图比例推，避免畸形
        var h = if (heightPx <= 200) (w / PANEL_ASPECT).roundToInt() else heightPx
        h = h.coerceIn(minH, maxH)

        val ratio = w.toFloat() / h.toFloat()
        when {
            ratio > MAX_ASPECT -> h = (w / MAX_ASPECT).roundToInt().coerceIn(minH, maxH)
            ratio < MIN_ASPECT -> {
                // 优先放宽宽度；屏幕太窄放不下时改为压缩高度，保证比例不畸形
                val wideW = (h * MIN_ASPECT).roundToInt()
                if (wideW <= maxW) w = wideW.coerceAtLeast(minW)
                else h = (w / MIN_ASPECT).roundToInt().coerceIn(minH, maxH)
            }
        }
        // 兜底：再确认按钮行装得下
        if (w < needPx) w = needPx.coerceAtMost(maxW)
        return w to h
    }

    /** 拖动边界：用当前真实屏幕，横竖屏切换后正确 */
    fun clampAnchor(value: Int, screenLen: Int, panelLen: Int): Int =
        value.coerceIn(0, (screenLen - panelLen).coerceAtLeast(0))
}
