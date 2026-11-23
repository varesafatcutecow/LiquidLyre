package com.liquidlyre.app.glass

/**
 * 滑块拖动增量累加的纯函数（不依赖 Android/Compose，可 JVM 单测）。
 *
 * 关键：这里把「当前值 + 本帧增量」直接算成新的绝对目标值，拖动方拿到后立即作为
 * 下一帧的 current。这样连续多帧 dragAmount 能正确累加，永远不会因为外部值回写慢
 * 而把增量加在旧值上（这正是"所有滑杆滑不动"的根因）。
 */
object SliderMath {
    /**
     * @param current     当前值（拖动真值源）
     * @param dragPx      本帧横向拖动像素（可正可负）
     * @param trackWidth  轨道像素宽，>0
     * @param range       取值区间
     * @param ltr         是否从左到右布局
     * @return 累加并 clamp 后的新值
     */
    fun accumulateDrag(
        current: Float,
        dragPx: Float,
        trackWidth: Int,
        range: ClosedFloatingPointRange<Float>,
        ltr: Boolean
    ): Float {
        if (trackWidth <= 0) return current.coerceIn(range)
        val delta = (range.endInclusive - range.start) * (dragPx / trackWidth)
        return (if (ltr) current + delta else current - delta).coerceIn(range)
    }

    /**
     * 取值 → 渲染进度 [0,1]，强制 clamp。
     *
     * spring 动画过程中 value 可能瞬时越界（冲到 start 以下或 end 以上）。
     * 若直接用 (value-start)/(end-start)，越界会让 progress < 0 或 > 1，
     * 进而 layout 里算出负数宽度 → Compose 抛 "Size(-137 x 23) is out of range" 崩溃。
     * 渲染进度绝不能越界，故在此统一夹住。
     */
    fun progress(value: Float, range: ClosedRange<Float>): Float {
        val span = range.endInclusive - range.start
        if (span <= 0f) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }

    /** 点击轨道：把落点像素换算成绝对取值 */
    fun valueAtPosition(
        positionPx: Float,
        trackWidth: Int,
        range: ClosedFloatingPointRange<Float>,
        ltr: Boolean
    ): Float {
        if (trackWidth <= 0) return range.start
        val delta = (range.endInclusive - range.start) * (positionPx / trackWidth)
        return (if (ltr) range.start + delta else range.endInclusive - delta).coerceIn(range)
    }
}
