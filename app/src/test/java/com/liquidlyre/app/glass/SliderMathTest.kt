package com.liquidlyre.app.glass

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证滑块拖动增量累加——这是"所有滑杆滑不动"的核心修复点。
 * 模拟逐帧 dragAmount，若每帧都把增量加在旧值上（bug 行为）会卡住；
 * 正确行为应逐帧累加到新值。
 */
class SliderMathTest {

    private val range = 0f..32f
    private val trackWidth = 320

    @Test
    fun `连续拖动增量正确累加不丢失`() {
        // 每帧右移 16px，range 32 / width 320 => 每帧 +1.6
        var current = 0f
        repeat(10) {
            current = SliderMath.accumulateDrag(current, 16f, trackWidth, range, ltr = true)
        }
        assertEquals(16f, current, 0.001f)
    }

    @Test
    fun `bug行为对照_每帧加在旧值上会卡住`() {
        // 反证：若错误实现每帧都算 range*(px/width) 加在固定旧值 0 上，
        // 10 帧后仍是 1.6，远小于正确值 16 —— 说明修复确有必要。
        val stuck = (range.endInclusive - range.start) * (16f / trackWidth)
        assertEquals(1.6f, stuck, 0.001f)
        assert(stuck < 16f)
    }

    @Test
    fun `拖动到上界正确clamp不溢出`() {
        var current = 0f
        repeat(50) {
            current = SliderMath.accumulateDrag(current, 100f, trackWidth, range, ltr = true)
        }
        assertEquals(32f, current, 0.001f)
    }

    @Test
    fun `拖动到下界正确clamp不为负`() {
        var current = 16f
        repeat(50) {
            current = SliderMath.accumulateDrag(current, -100f, trackWidth, range, ltr = true)
        }
        assertEquals(0f, current, 0.001f)
    }

    @Test
    fun `rtl布局拖动方向反转`() {
        val ltr = SliderMath.accumulateDrag(10f, 16f, trackWidth, range, ltr = true)
        val rtl = SliderMath.accumulateDrag(10f, 16f, trackWidth, range, ltr = false)
        assertEquals(11.6f, ltr, 0.001f)
        assertEquals(8.4f, rtl, 0.001f)
    }

    @Test
    fun `点击轨道落点换算正确`() {
        assertEquals(0f, SliderMath.valueAtPosition(0f, trackWidth, range, true), 0.001f)
        assertEquals(16f, SliderMath.valueAtPosition(160f, trackWidth, range, true), 0.001f)
        assertEquals(32f, SliderMath.valueAtPosition(320f, trackWidth, range, true), 0.001f)
        // rtl：最左=上界，最右=下界
        assertEquals(32f, SliderMath.valueAtPosition(0f, trackWidth, range, false), 0.001f)
        assertEquals(0f, SliderMath.valueAtPosition(320f, trackWidth, range, false), 0.001f)
    }

    @Test
    fun `零宽轨道不崩溃返回当前值`() {
        assertEquals(10f, SliderMath.accumulateDrag(10f, 16f, 0, range, true), 0.001f)
        assertEquals(0f, SliderMath.valueAtPosition(100f, 0, range, true), 0.001f)
    }

    // ---- progress 越界防护：v1.6.4「滑到设置闪退」根因 ----

    @Test
    fun `progress正常值线性映射`() {
        assertEquals(0f, SliderMath.progress(0f, range), 0.001f)
        assertEquals(0.5f, SliderMath.progress(16f, range), 0.001f)
        assertEquals(1f, SliderMath.progress(32f, range), 0.001f)
    }

    @Test
    fun `progress低于下界clamp到0_杜绝负宽度`() {
        // spring 过冲会让 value 冲到 start 以下 → 未 clamp 会得到负 progress → layout 负宽度崩溃
        assertEquals(0f, SliderMath.progress(-0.5f, range), 0.001f)
        assertEquals(0f, SliderMath.progress(-137f, range), 0.001f)
    }

    @Test
    fun `progress高于上界clamp到1`() {
        assertEquals(1f, SliderMath.progress(40f, range), 0.001f)
        assertEquals(1f, SliderMath.progress(1000f, range), 0.001f)
    }
}
