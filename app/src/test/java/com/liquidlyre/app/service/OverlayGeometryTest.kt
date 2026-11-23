package com.liquidlyre.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗几何单测 —— 盯死用户反馈的畸形/裁切/设置不生效：
 * 1) 面板装得下三个圆按钮（不裁切、光环不溢出）
 * 2) 宽度和高度设置都真正生效（旧版高度被直接丢弃）
 * 3) 比例钳制，任何设置都不会变横扁条/竖条
 * 4) 横屏边界用真实屏幕宽，可以拖到右半屏
 */
class OverlayGeometryTest {

    private val d = 2.75f          // iQOO15 类高密度屏
    private val landscapeW = 3168
    private val landscapeH = 1440

    @Test
    fun `默认1000x575 - 比例1点74且装得下按钮`() {
        val (w, h) = OverlayGeometry.panelSize(1000, 575, landscapeW, landscapeH, d)
        assertEquals(1000, w)
        assertEquals(575, h)
        assertEquals(1.74f, w.toFloat() / h, 0.02f)
        assertTrue(
            "面板内宽 ${w / d}dp 必须大于按钮行需求 ${OverlayGeometry.contentNeedDp()}dp",
            w / d > OverlayGeometry.contentNeedDp()
        )
    }

    @Test
    fun `旧默认高度150自动修正为参考比例`() {
        // 存量用户设置里还是 1000x150 的横扁条，必须自动收敛，不能继续畸形
        val (w, h) = OverlayGeometry.panelSize(1000, 150, landscapeW, landscapeH, d)
        assertEquals(1.74f, w.toFloat() / h, 0.05f)
        assertTrue("高度应远大于旧的 150px，实际 $h", h > 400)
    }

    @Test
    fun `高度设置真正生效`() {
        // 旧版 panelSize 只吃 width，height 被无视 —— 这条断言就是防回归
        val (_, h1) = OverlayGeometry.panelSize(1000, 800, landscapeW, landscapeH, d)
        val (_, h2) = OverlayGeometry.panelSize(1000, 560, landscapeW, landscapeH, d)
        assertEquals(800, h1)
        assertEquals(560, h2)
        assertTrue(h1 > h2)
    }

    @Test
    fun `比例过扁被收敛到2点6以内`() {
        val (w, h) = OverlayGeometry.panelSize(2000, 400, landscapeW, landscapeH, d)
        val ratio = w.toFloat() / h
        assertTrue("ratio=$ratio 不应超过 ${OverlayGeometry.MAX_ASPECT}", ratio <= OverlayGeometry.MAX_ASPECT + 0.01f)
    }

    @Test
    fun `比例过瘦被放宽到1点15以上`() {
        val (w, h) = OverlayGeometry.panelSize(800, 900, landscapeW, landscapeH, d)
        val ratio = w.toFloat() / h
        assertTrue("ratio=$ratio 不应小于 ${OverlayGeometry.MIN_ASPECT}", ratio >= OverlayGeometry.MIN_ASPECT - 0.01f)
        assertTrue("同时仍要装得下按钮", w / d > OverlayGeometry.contentNeedDp())
    }

    @Test
    fun `极窄设置被抬到能装下按钮`() {
        val (w, _) = OverlayGeometry.panelSize(200, 500, landscapeW, landscapeH, d)
        assertTrue("宽度 ${w}px 至少应为按钮需求 ${OverlayGeometry.contentNeedDp() * d}px",
            w >= (OverlayGeometry.contentNeedDp() * d).toInt())
    }

    @Test
    fun `超屏宽度被限制在屏幕94百分比内`() {
        val (w, _) = OverlayGeometry.panelSize(5000, 1000, 1000, 2000, d)
        assertTrue("宽 $w 应 <= ${(1000 * 0.94f).toInt()}", w <= (1000 * 0.94f).toInt())
    }

    @Test
    fun `横屏拖动边界用真实宽可达右半屏`() {
        val (w, _) = OverlayGeometry.panelSize(1000, 575, landscapeW, landscapeH, d)
        val rightLimit = OverlayGeometry.clampAnchor(Int.MAX_VALUE, landscapeW, w)
        assertTrue("横屏最大 X=$rightLimit 应 > 1440（能到右半屏）", rightLimit > 1440)
    }

    @Test
    fun `锚点clamp不越界且不为负`() {
        assertEquals(0, OverlayGeometry.clampAnchor(-100, 3168, 1000))
        assertEquals(2168, OverlayGeometry.clampAnchor(99999, 3168, 1000))
        assertEquals(500, OverlayGeometry.clampAnchor(500, 3168, 1000))
    }
}
