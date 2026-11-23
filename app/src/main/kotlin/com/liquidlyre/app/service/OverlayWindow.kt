package com.liquidlyre.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.liquidlyre.app.engine.PlayState
import com.liquidlyre.app.engine.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 游戏内悬浮窗 v4。
 *
 * v1.3 的致命 bug：折叠用的 ♪ TextView 被 addView 时没给 LayoutParams，
 * FrameLayout 默认就是 MATCH_PARENT×MATCH_PARENT —— 它铺满整个面板且 ACTION_DOWN 返回 true，
 * 把面板上每一次触摸全吞了。所以播放/上一首/下一首/右上灰点全都"点了没反应，只瞎Q弹"。
 *
 * 本版修复：
 *  - ♪ 圆点显式 dotD×dotD 居中；展开态 INVISIBLE（不参与命中测试），折叠态才接管触摸
 *  - 折叠点用位移判定点击 vs 拖动（touchSlop），不用速度阈值
 *  - 展开态内容淡出到 0 后 INVISIBLE，折叠态不再拦截
 *  - 播放键按当前状态发 PLAY / PAUSE，图标与文字同步
 *  - 几何宽高都真正生效，比例钳制防畸形，最小宽度保证按钮行装得下
 *  - 拖动结束把位置写回设置，设置页 X/Y 也真正能移动窗口
 *  - addView 失败写诊断，不再静默吞异常
 *
 * 动效保持蒸馏结论：折叠=几何连续形变（圆角全程<=h/2）、折叠 smooth / 展开 bouncy、
 * 拖动保留速度 fling + 边界回弹、X/Y 双弹簧果冻。
 */
class OverlayWindow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val wm: WindowManager
) {
    private var root: FrameLayout? = null
    private var panelBg: android.graphics.drawable.GradientDrawable? = null
    private var content: LinearLayout? = null
    private var dotGlyph: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false
    private var viewScope: CoroutineScope? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    // 展开态几何（px）
    private var fullW = dp(360f)
    private var fullH = dp(207f)     // 参考图 1.74:1
    private val dotD get() = fullH.coerceIn(dp(48f), dp(72f))   // 折叠点直径
    private val panelCorner get() = dp(28f)

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val d = context.resources.displayMetrics
            d.widthPixels to d.heightPixels
        }
    }
    private fun anchorLimX(): Int { val (sw, _) = screenSize(); return (sw - fullW).coerceAtLeast(0) }
    private fun anchorLimY(): Int { val (_, sh) = screenSize(); return (sh - fullH).coerceAtLeast(0) }

    private var anchorX = 0
    private var anchorY = 0

    // 弹簧内核
    private val morph = Spring.smooth()
    private val flingX = Spring(0f, 90f, 16f)
    private val flingY = Spring(0f, 90f, 16f)
    private val jellyX = Spring.jellyX()
    private val jellyY = Spring.jellyY()
    private var morphValue = 0f
    private var jellyXv = 1f
    private var jellyYv = 1f

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show(
        width: Int, height: Int, x: Int, y: Int,
        draggable: Boolean,
        onMoved: (Int, Int) -> Unit = { _, _ -> },
        onAction: (String) -> Unit
    ) {
        hide()
        val (sw, sh) = screenSize()
        val (pw, ph) = OverlayGeometry.panelSize(width, height, sw, sh, density)
        fullW = pw
        fullH = ph
        anchorX = OverlayGeometry.clampAnchor(x, sw, fullW)
        anchorY = OverlayGeometry.clampAnchor(y, sh, fullH)
        morphValue = 0f
        jellyXv = 1f; jellyYv = 1f
        jellyX.jump(1f); jellyY.jump(1f); morph.jump(0f)
        flingX.jump(0f); flingY.jump(0f)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val p = WindowManager.LayoutParams(
            fullW, fullH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = anchorX
            this.y = anchorY
        }
        params = p

        val rootView = FrameLayout(context)
        root = rootView
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE61F1D26.toInt())
            cornerRadius = panelCorner.toFloat()
            setStroke(1, 0x1AFFFFFF.toInt())
        }
        panelBg = bg
        rootView.background = bg

        val contentView = buildContent(onAction)
        content = contentView
        rootView.addView(
            contentView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // ♪ 折叠圆点：必须显式 dotD×dotD 居中！否则 MATCH_PARENT 铺满会吞掉所有按钮的触摸
        val glyph = TextView(context).apply {
            text = "♪"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.INVISIBLE
        }
        dotGlyph = glyph
        rootView.addView(glyph, FrameLayout.LayoutParams(dotD, dotD, Gravity.CENTER))
        attachDotDrag(glyph, onMoved)

        try {
            wm.addView(rootView, p)
            added = true
        } catch (t: Throwable) {
            added = false
            PlayerState.diag("悬浮窗添加失败：${t.javaClass.simpleName} ${t.message ?: ""}")
        }
        applyMorph(0f)
    }

    /** 展开态内容：标题 + 三个圆按钮（参考图布局，箭头紧贴圆外侧） */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildContent(onAction: (String) -> Unit): LinearLayout {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 标题栏（拖拽把手）+ 右上折叠圆点
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22f), dp(16f), dp(14f), 0)
        }
        val title = TextView(context).apply {
            text = "未选曲目（待命）"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 右上角灰点折叠钮：24dp 圆，点击收成圆点
        val foldDot = TextView(context).apply {
            val d = dp(24f)
            layoutParams = LinearLayout.LayoutParams(d, d).apply { marginStart = dp(8f) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFB9B7C4.toInt())
            }
            isClickable = true
            isFocusable = false
            setOnClickListener { fold() }
        }
        header.addView(title)
        header.addView(foldDot)

        // 按钮行：|◀ [上一首] [▶播放] [下一首] ▶|
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setPadding(dp(6f), 0, dp(6f), dp(16f))
        }
        val prevBtn = roundBtn("|◀", "上一首", 52f, primary = false) {
            onAction(LyreAccessibilityService.ACTION_PREV)
        }
        val playBtn = roundBtn("▶", "播放", 84f, primary = true) {
            val playing = PlayerState.snap.value.state == PlayState.PLAYING
            onAction(if (playing) LyreAccessibilityService.ACTION_PAUSE else LyreAccessibilityService.ACTION_PLAY)
        }
        val nextBtn = roundBtn("▶|", "下一首", 52f, primary = false) {
            onAction(LyreAccessibilityService.ACTION_NEXT)
        }
        val prevIcon = jumpIcon("|◀", onAction, LyreAccessibilityService.ACTION_PREV)
        val nextIcon = jumpIcon("▶|", onAction, LyreAccessibilityService.ACTION_NEXT)
        row.addView(prevIcon)
        row.addView(prevBtn)
        row.addView(playBtn)
        row.addView(nextBtn)
        row.addView(nextIcon)

        col.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        attachPanelDrag(title)

        val vs = viewScope ?: scope
        vs.launch {
            PlayerState.snap.map { it.state }.distinctUntilChanged().collect { st ->
                title.text = "${PlayerState.snap.value.current?.name ?: "未选曲目"}（${stateLabel(st)}）"
                val face = playBtn.getChildAt(0) as? LinearLayout
                face?.findViewWithTag<TextView>("icon")?.text = if (st == PlayState.PLAYING) "❚❚" else "▶"
                (face?.getChildAt(1) as? TextView)?.text = if (st == PlayState.PLAYING) "暂停" else "播放"
            }
        }
        return col
    }

    private fun stateLabel(st: PlayState) = when (st) {
        PlayState.PLAYING -> "演奏中"
        PlayState.PAUSED -> "暂停"
        PlayState.STOPPED -> "已急停"
        PlayState.ERROR -> "错误"
        else -> "待命"
    }

    /** 圆按钮：外层容器（点击热区）+ 内部圆面；primary 带光环 */
    private fun roundBtn(icon: String, label: String, sizeDp: Float, primary: Boolean, click: () -> Unit): LinearLayout {
        val d = dp(sizeDp)
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(d, d).apply {
                val m = dp(6f); setMargins(m, 0, m, 0)
            }
            setOnClickListener { click() }
        }
        val face = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (primary) 0xFF3A3746.toInt() else 0xFF2B2934.toInt())
                val strokeW: Int = if (primary) dp(2f) else 1
                val strokeColor: Int = if (primary) 0x66FFFFFF.toInt() else 0x22FFFFFF.toInt()
                setStroke(strokeW, strokeColor)
            }
        }
        val iconView = TextView(context).apply {
            text = icon
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (primary) 32f else 18f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            tag = "icon"
        }
        face.addView(iconView)
        if (label.isNotEmpty()) {
            face.addView(TextView(context).apply {
                text = label; setTextColor(0xFFEDEBF2.toInt()); textSize = 12f; gravity = Gravity.CENTER
            })
        }
        outer.addView(face)
        return outer
    }

    private fun jumpIcon(text: String, onAction: (String) -> Unit, action: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(0xFFD8D6E0.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding(dp(6f), dp(18f), dp(6f), dp(18f))
            setOnClickListener { onAction(action) }
        }

    // ============ 形变 ============
    private fun fold() {
        morph.configure(300f, 36f)  // smooth：干脆收拢
        morph.setTarget(1f, onUpdate = { m -> applyMorph(m) })
    }
    private fun unfold() {
        morph.configure(340f, 20f)  // bouncy：弹出一下
        morph.setTarget(0f, onUpdate = { m -> applyMorph(m) })
    }

    /** 每帧应用 morph + 果冻；屏幕边界实时取，横竖屏切换后正确 */
    private fun applyMorph(mRaw: Float) {
        morphValue = mRaw
        val m = mRaw.coerceIn(0f, 1f)
        val w = (fullW + (dotD - fullW) * m).roundToInt().coerceAtLeast(dotD)
        val h = (fullH + (dotD - fullH) * m).roundToInt().coerceAtLeast(dotD)
        // 圆角全程等于真实几何，永不折痕
        val corner = (panelCorner + (dotD / 2f - panelCorner) * m).coerceAtMost(h / 2f)
        panelBg?.cornerRadius = corner
        val p = params ?: return
        p.width = w; p.height = h
        val (sw, sh) = screenSize()
        p.x = (anchorX + (fullW - dotD) * m).roundToInt().coerceIn(0, (sw - w).coerceAtLeast(0))
        p.y = (anchorY + (fullH - dotD) * m).roundToInt().coerceIn(0, (sh - h).coerceAtLeast(0))
        try { wm.updateViewLayout(root, p) } catch (_: Throwable) {}

        // 内容淡出到 0 后直接 INVISIBLE，折叠态不再拦截触摸
        val alpha = (1f - m * 1.6f).coerceIn(0f, 1f)
        content?.alpha = alpha
        content?.visibility = if (alpha <= 0.01f) View.INVISIBLE else View.VISIBLE
        content?.scaleX = jellyXv
        content?.scaleY = jellyYv
        // 圆点：m<0.35 完全隐藏（展开态不参与命中测试，按钮才收得到事件）
        dotGlyph?.alpha = ((m - 0.35f) / 0.65f).coerceIn(0f, 1f)
        dotGlyph?.visibility = if (m < 0.35f) View.INVISIBLE else View.VISIBLE
    }

    // ============ 拖动 ============

    /** 展开态：拖标题栏移动面板（只绑 title，不吞子按钮事件） */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPanelDrag(handle: View) {
        val vt = VelocityTracker.obtain()
        var sx = 0f; var sy = 0f
        handle.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    flingX.cancel(); flingY.cancel()
                    vt.clear(); vt.addMovement(e)
                    sx = e.rawX - p.x
                    sy = e.rawY - p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt.addMovement(e); vt.computeCurrentVelocity(1000)
                    val vx = vt.xVelocity; val vy = vt.yVelocity
                    anchorX = (e.rawX - sx).roundToInt().coerceIn(0, anchorLimX())
                    anchorY = (e.rawY - sy).roundToInt().coerceIn(0, anchorLimY())
                    applyMorph(morphValue)
                    val stretch = (abs(vx) / 2500f).coerceIn(0f, 0.18f)
                    jellyX.setTarget(1f + stretch, onUpdate = { v -> jellyXv = v; applyMorph(morphValue) })
                    jellyY.setTarget(1f - stretch * 1.4f, onUpdate = { v -> jellyYv = v; applyMorph(morphValue) })
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt.computeCurrentVelocity(1000)
                    val vx = vt.xVelocity.coerceIn(-3500f, 3500f)
                    val vy = vt.yVelocity.coerceIn(-3500f, 3500f)
                    flingX.jump(anchorX.toFloat()); flingY.jump(anchorY.toFloat())
                    flingX.kick(vx * 0.10f, onUpdate = { a ->
                        anchorX = a.roundToInt().coerceIn(0, anchorLimX()); applyMorph(morphValue)
                    })
                    flingY.kick(vy * 0.08f, onUpdate = { a ->
                        anchorY = a.roundToInt().coerceIn(0, anchorLimY()); applyMorph(morphValue)
                    })
                    jellyX.setTarget(1f, onUpdate = { v -> jellyXv = v; applyMorph(morphValue) })
                    jellyY.setTarget(1f, onUpdate = { v -> jellyYv = v; applyMorph(morphValue) })
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 折叠点：折叠态拖它移动窗口，轻点（位移 < touchSlop）展开。
     * 展开态直接 return false 不消费事件，避免抢按钮的触摸。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDotDrag(handle: View, onMoved: (Int, Int) -> Unit) {
        val vt = VelocityTracker.obtain()
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var sx = 0f; var sy = 0f
        var downX = 0f; var downY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            if (morphValue < 0.35f) return@setOnTouchListener false   // 展开态不接管
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    flingX.cancel(); flingY.cancel()
                    vt.clear(); vt.addMovement(e)
                    downX = e.rawX; downY = e.rawY
                    sx = e.rawX - p.x
                    sy = e.rawY - p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moved = true
                    vt.addMovement(e); vt.computeCurrentVelocity(1000)
                    val m = morphValue.coerceIn(0f, 1f)
                    val vx = vt.xVelocity; val vy = vt.yVelocity
                    anchorX = (e.rawX - sx - (fullW - dotD) * m).roundToInt().coerceIn(0, anchorLimX())
                    anchorY = (e.rawY - sy - (fullH - dotD) * m).roundToInt().coerceIn(0, anchorLimY())
                    applyMorph(morphValue)
                    val stretch = (abs(vx) / 2500f).coerceIn(0f, 0.18f)
                    jellyX.setTarget(1f + stretch, onUpdate = { v -> jellyXv = v; applyMorph(morphValue) })
                    jellyY.setTarget(1f - stretch * 1.4f, onUpdate = { v -> jellyYv = v; applyMorph(morphValue) })
                    true
                }
                MotionEvent.ACTION_UP -> {
                    vt.computeCurrentVelocity(1000)
                    if (!moved) {
                        unfold()
                    } else {
                        val vx = vt.xVelocity.coerceIn(-3500f, 3500f)
                        val vy = vt.yVelocity.coerceIn(-3500f, 3500f)
                        flingX.jump(anchorX.toFloat()); flingY.jump(anchorY.toFloat())
                        flingX.kick(vx * 0.10f, onUpdate = { a ->
                            anchorX = a.roundToInt().coerceIn(0, anchorLimX()); applyMorph(morphValue)
                        })
                        flingY.kick(vy * 0.08f, onUpdate = { a ->
                            anchorY = a.roundToInt().coerceIn(0, anchorLimY()); applyMorph(morphValue)
                        })
                        onMoved(anchorX, anchorY)
                    }
                    jellyX.setTarget(1f, onUpdate = { v -> jellyXv = v; applyMorph(morphValue) })
                    jellyY.setTarget(1f, onUpdate = { v -> jellyYv = v; applyMorph(morphValue) })
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** 设置页改 X/Y 时移动窗口（不重建视图，避免闪烁） */
    fun moveTo(x: Int, y: Int) {
        val (sw, sh) = screenSize()
        anchorX = OverlayGeometry.clampAnchor(x, sw, fullW)
        anchorY = OverlayGeometry.clampAnchor(y, sh, fullH)
        applyMorph(morphValue)
    }

    fun hide() {
        if (added && root != null) {
            try { wm.removeView(root) } catch (_: Throwable) {}
        }
        added = false
        root = null; content = null; dotGlyph = null; panelBg = null; params = null
        morph.cancel(); flingX.cancel(); flingY.cancel(); jellyX.cancel(); jellyY.cancel()
        viewScope?.cancel(); viewScope = null
    }
}
