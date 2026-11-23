package com.liquidlyre.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.liquidlyre.app.data.KeyMap
import com.liquidlyre.app.engine.PlayerState
import kotlin.math.roundToInt

/**
 * 全屏琴键校准层（TYPE_ACCESSIBILITY_OVERLAY，盖在游戏上）。
 *
 * 旧的 Compose「校准对话框」是坏的：21 个点在 Row 里 SpaceEvenly 排列，再用 `offset / 8`
 * 的魔法数位移 —— 跟真实屏幕坐标没有任何对应关系，用户根本没法把点拖到琴键上。
 *
 * 这里改成真正可用的做法：
 *  - 全屏透明层，能直接看到游戏画面
 *  - 21 个圆点按【当前已保存坐标 / 默认坐标】摆在真实屏幕位置上，用 rawX/rawY 拖动
 *  - 每个点显示键名，点住的点放大 + 显示实时坐标
 *  - 顶部工具栏：提示 + 保存 + 取消（取消不改任何值）
 *  - 保存时一次性写回 21 个坐标（不逐键写 DataStore）
 */
class CalibrationOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val initial: Map<String, PointF>,
    private val onSave: (Map<String, PointF>) -> Unit,
    private val onClose: () -> Unit
) {
    private var root: FrameLayout? = null
    private var added = false
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val dotSize = dp(44f)
    private val dots = HashMap<String, TextView>()
    private val positions = HashMap<String, PointF>()
    private var hint: TextView? = null

    private fun screen(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.currentWindowMetrics.bounds
        b.width() to b.height()
    } else {
        @Suppress("DEPRECATION")
        val d = context.resources.displayMetrics
        d.widthPixels to d.heightPixels
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        hide()
        val (sw, sh) = screen()
        val rootView = FrameLayout(context)
        root = rootView

        // 半透明底：既能看到游戏，又拦住误触（点空白处不会点进游戏）
        rootView.setBackgroundColor(0x33000000)
        rootView.isClickable = true

        // ===== 顶部工具栏 =====
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                setColor(0xE61A1820.toInt())
                cornerRadius = dp(18f).toFloat()
            }
        }
        val title = TextView(context).apply {
            text = "拖 21 个点到游戏琴键上"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveBtn = TextView(context).apply {
            text = "保存"
            setTextColor(0xFF7CFF9B.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            isClickable = true
            setOnClickListener { save() }
        }
        val cancelBtn = TextView(context).apply {
            text = "取消"
            setTextColor(0xFFFF9C9C.toInt())
            textSize = 15f
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            isClickable = true
            setOnClickListener { close() }
        }
        bar.addView(title)
        bar.addView(saveBtn)
        bar.addView(cancelBtn)
        rootView.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP; topMargin = dp(10f); marginStart = dp(12f); marginEnd = dp(12f) }
        )

        hint = TextView(context).apply {
            text = "点住圆点拖动 · 松手定位"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        rootView.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(28f) }
        )

        // ===== 21 个点 =====
        KeyMap.ALL_KEYS.forEach { key ->
            val saved = initial[key]
            val def = KeyMap.defaultPoint(key)
            val p0 = saved ?: def ?: PointF(sw / 2f, sh / 2f)
            val x = p0.x.coerceIn(dp(26f).toFloat(), (sw - dp(26f)).toFloat())
            val y = p0.y.coerceIn(dp(26f).toFloat(), (sh - dp(26f)).toFloat())
            positions[key] = PointF(x, y)

            val dot = TextView(context).apply {
                text = key
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (saved != null) 0xCC2F6BFF.toInt() else 0xCC6B3FA0.toInt())
                    setStroke(dp(2f), 0xFFFFFFFF.toInt())
                }
                isClickable = true
            }
            dots[key] = dot

            val lp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (x - dotSize / 2f).roundToInt()
                topMargin = (y - dotSize / 2f).roundToInt()
            }
            rootView.addView(dot, lp)
            attachDrag(dot, key)
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try {
            wm.addView(rootView, p)
            added = true
        } catch (t: Throwable) {
            added = false
            PlayerState.diag("校准层添加失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            onClose()
        }
    }

    /** 拖动：rawX/rawY 就是屏幕物理坐标，直接写回游戏坐标系 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(dot: TextView, key: String) {
        var startRawX = 0f
        var startRawY = 0f
        var startLeft = 0
        var startTop = 0
        dot.setOnTouchListener { v, e ->
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = e.rawX; startRawY = e.rawY
                    startLeft = lp.leftMargin; startTop = lp.topMargin
                    v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(90).start()
                    hint?.text = "$key（${positions[key]?.x?.roundToInt()} , ${positions[key]?.y?.roundToInt()}）"
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = startLeft + (e.rawX - startRawX)
                    val ny = startTop + (e.rawY - startRawY)
                    lp.leftMargin = nx.roundToInt()
                    lp.topMargin = ny.roundToInt()
                    v.layoutParams = lp
                    positions[key] = PointF(
                        nx + dotSize / 2f,
                        ny + dotSize / 2f
                    )
                    hint?.text = "$key（${positions[key]?.x?.roundToInt()} , ${positions[key]?.y?.roundToInt()}）"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    // 写回时也 clamp，防止拖出屏幕后被系统截断导致坐标与实际点不符
                    val (sw, sh) = screen()
                    val cur = positions[key] ?: return@setOnTouchListener true
                    positions[key] = PointF(
                        cur.x.coerceIn(0f, sw.toFloat()),
                        cur.y.coerceIn(0f, sh.toFloat())
                    )
                    true
                }
                else -> false
            }
        }
    }

    private fun save() {
        val copy = positions.mapValues { (_, v) -> PointF(v.x, v.y) }
        hide()
        onSave(copy)
    }

    private fun close() {
        hide()
        onClose()
    }

    fun hide() {
        if (added && root != null) {
            try { wm.removeView(root) } catch (_: Throwable) {}
        }
        added = false
        root = null
        dots.clear()
        positions.clear()
        hint = null
    }

    fun isShowing(): Boolean = added
}
