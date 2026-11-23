package com.liquidlyre.app.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    /**
     * 拖动期的内部真值源：同步更新，不再依赖外部 value 回写（DataStore 磁盘写太慢，
     * 每帧 targetValue 追不上会导致增量累加到旧值上、滑杆卡住）。
     * valueAnimation 仍负责渲染插值，但 targetValue 一律读这里。
     */
    private var dragTarget: Float = initialValue.coerceIn(valueRange)

    val value: Float get() = valueAnimation.value
    // spring 过冲会让 value 瞬时越界，必须 clamp 到 [0,1]，否则 LiquidSlider 的
    // layout 会算出负宽度 → "Size(-x) is out of range" 崩溃（v1.6.4 闪退根因）。
    val progress: Float get() = SliderMath.progress(value, valueRange)
    val targetValue: Float get() = dragTarget
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /** 外部值同步（来自 DataStore 回写）：更新内部真值源 + 平滑动画 */
    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        dragTarget = targetValue
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    /**
     * 拖动期直接把增量结果同步到真值源并瞬间 snap 渲染，不经过动画队列。
     * 这样下一帧 onDrag 读到的 targetValue 就是刚累加过的值，增量不会丢。
     */
    fun snapToValue(value: Float) {
        val v = value.coerceIn(valueRange)
        dragTarget = v
        // snapTo 走 mutatorMutex 保证与进行中的 animateTo 不冲突
        animationScope.launch {
            mutatorMutex.mutate {
                valueAnimation.snapTo(v)
            }
        }
        updateVelocity()
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        dragTarget = targetValue
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            SystemClock.uptimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
