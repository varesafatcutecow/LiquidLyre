package com.liquidlyre.app.service

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.cos

/**
 * 纯 View 弹簧内核（PiliGlass 蒸馏落地，零额外依赖）。
 *
 * 铁律（来自 dynamic-glass-and-motion.md 第三节）：
 * - 保留速度：自己维护 value/target/velocity，每帧用弹簧积分步进，继承上一帧速度；
 *   绝不在每个 pointer 事件调 animateTo 从 t=0 重启模拟（那样跟手会卡住、抬手才跳）。
 * - 显示/隐藏用不同弹簧：showing 弹（欠阻尼过冲），hiding 干脆（临界阻尼不过冲）。
 * - 果冻：X/Y 两轴用不同阻尼比，从速度/刹车推导拉伸挤压，面积近似守恒。
 *
 * 采用解析解积分（半隐式欧拉 + 刚度/阻尼），数值稳定，足够驱动悬浮窗形变。
 */
class Spring(
    initialValue: Float = 0f,
    private var stiffness: Float = 220f,   // k：越大越快
    private var damping: Float = 22f       // c：越小越弹，=2√k 为临界阻尼
) {
    var value: Float = initialValue
        private set

    /** 运行时切换弹簧参数（显示 bouncy / 隐藏 smooth 用同一实例不同参数） */
    fun configure(k: Float, c: Float) { stiffness = k; damping = c }
    var target: Float = initialValue
        private set
    var velocity: Float = 0f
        private set

    private var running = false
    private val callback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 1f / 60f else ((frameTimeNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.032f)
            lastFrameNs = frameTimeNs
            step(dt)
            if (abs(velocity) > 0.01f || abs(target - value) > 0.005f) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                value = target
                velocity = 0f
                running = false
                lastFrameNs = 0L
                onSettle?.invoke(value)
            }
        }
    }
    private var lastFrameNs = 0L
    private var onUpdate: ((Float) -> Unit)? = null
    private var onSettle: ((Float) -> Unit)? = null

    /** 设目标并启动（保留当前速度） */
    fun setTarget(v: Float, onUpdate: (Float) -> Unit, onSettle: ((Float) -> Unit)? = null) {
        this.target = v
        this.onUpdate = onUpdate
        this.onSettle = onSettle
        if (!running) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    /** 拖动中直接跟手：改 value 不清零 velocity（保留速度供松手 fling） */
    fun setValueDirect(v: Float, onUpdate: (Float) -> Unit) {
        value = v
        target = v
        onUpdate(v)
    }

    /** 踢一脚：注入初速度（用于极快点击的下陷回弹、松手 fling） */
    fun kick(v: Float, onUpdate: (Float) -> Unit) {
        velocity = v
        onUpdate(value)
        if (!running) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun jump(v: Float) {
        value = v
        target = v
        velocity = 0f
        running = false
        lastFrameNs = 0L
    }

    private fun step(dt: Float) {
        // 半隐式欧拉：a = (-k(x-target) - c·v)，先更新 v 再更新 x，继承上一帧速度
        val a = -stiffness * (value - target) - damping * velocity
        velocity += a * dt
        value += velocity * dt
        onUpdate?.invoke(value)
    }

    fun cancel() {
        running = false
        lastFrameNs = 0L
        onUpdate = null
        onSettle = null
    }

    companion object {
        // PiliGlass 参数体系映射成刚度/阻尼（k=ω², c=2ζω, ω≈2π/duration*1000）
        /** 按下 snappy：微弹 */
        fun snappy() = Spring(stiffness = 520f, damping = 34f)
        /** 隐藏 smooth：干脆无过冲（ζ≈1） */
        fun smooth() = Spring(stiffness = 300f, damping = 36f)
        /** 显示 bouncy：弹一下（ζ≈0.55） */
        fun bouncy() = Spring(stiffness = 340f, damping = 20f)
        /** 松手 jelly：Q 弹（ζ≈0.4） */
        fun jelly() = Spring(stiffness = 260f, damping = 13f)
        /** 果冻 X 轴：阻尼更低，扭动更明显 */
        fun jellyX() = Spring(stiffness = 260f, damping = 12f)
        /** 果冻 Y 轴：阻尼略高，与 X 形成面积守恒的扭动 */
        fun jellyY() = Spring(stiffness = 260f, damping = 14f)
    }
}
