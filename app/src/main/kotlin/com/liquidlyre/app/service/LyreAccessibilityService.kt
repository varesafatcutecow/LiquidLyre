package com.liquidlyre.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent
import com.liquidlyre.app.data.ScoreRepository
import com.liquidlyre.app.data.UserPrefs
import com.liquidlyre.app.engine.PlayState
import com.liquidlyre.app.engine.PlaybackEngine
import com.liquidlyre.app.engine.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 核心无障碍服务：
 * - 同一服务内 TYPE_ACCESSIBILITY_OVERLAY 挂悬浮窗
 * - 500ms 轮询 getWindows 做前台检测，窗口变化事件即时复检
 * - 失焦急停：cancel 手势、清空调度、状态归位
 * - 悬浮窗广播 → 加载曲目 → 三闸门校验 → 播放
 */
class LyreAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: UserPrefs
    private lateinit var repo: ScoreRepository
    private lateinit var overlay: OverlayWindow
    private var receiver: PlayerActionReceiver? = null
    private var latestCfg: OverlayConfig? = null
    private var calibration: CalibrationOverlay? = null

    override fun onCreate() {
        super.onCreate()
        prefs = UserPrefs(this)
        repo = ScoreRepository(this)
        overlay = OverlayWindow(this, scope, getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
        PlaybackEngine.attach(this, prefs, scope)
        PlayerState.requireA11y(true)

        receiver = PlayerActionReceiver { action -> handleAction(action) }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY); addAction(ACTION_PAUSE); addAction(ACTION_STOP)
            addAction(ACTION_PREV); addAction(ACTION_NEXT); addAction(ACTION_QUERY)
            addAction(ACTION_CALIBRATE); addAction(ACTION_TEST_TAP)
        }
        // targetSdk 33+ 动态注册必须显式声明 exported，否则 SecurityException 直接闪退
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // 悬浮窗：设置变更即时生效。
        // 宽/高/开关变化 → 重建视图；只有 X/Y 变化 → 直接移动（避免拖动时整窗重建闪烁）。
        scope.launch {
            combine(
                prefs.overlayEnabled, prefs.overlayW, prefs.overlayH,
                prefs.overlayX, prefs.overlayY
            ) { enabled, w, h, x, y ->
                OverlayConfig(enabled, w, h, x, y)
            }.distinctUntilChanged().collect { cfg ->
                val prev = latestCfg
                latestCfg = cfg
                val rebuild = prev == null ||
                    prev.enabled != cfg.enabled ||
                    prev.w != cfg.w || prev.h != cfg.h
                if (rebuild) {
                    applyOverlay(cfg)
                } else if (cfg.enabled && (prev.x != cfg.x || prev.y != cfg.y)) {
                    overlay.moveTo(cfg.x, cfg.y)
                }
            }
        }
        scope.launch { foregroundLoop() }
        // 服务刚开时补探一次当前前台包名（用户可能是开着游戏才来开无障碍的，
        // 那时不会再有 WINDOW_STATE_CHANGED 事件，状态灯会一直红）
        PlaybackEngine.noteForegroundPackage(rootInActiveWindow?.packageName?.toString())
        // 无障碍开启即常驻通知栏（此前只有点播放才起，用户反馈“没有通知栏”）
        KeepAliveService.start(this)
        PlayerState.toast("璃音服务已开启")
    }

    private data class OverlayConfig(
        val enabled: Boolean, val w: Int, val h: Int,
        val x: Int, val y: Int
    )

    private fun applyOverlay(cfg: OverlayConfig) {
        if (cfg.enabled) {
            overlay.show(
                cfg.w, cfg.h, cfg.x, cfg.y, false,
                onMoved = { nx, ny ->
                    // 拖动结束写回设置：下次重建 / 旋转屏幕后不会跳回旧位置
                    scope.launch {
                        prefs.setInt(UserPrefs.OVERLAY_X, nx)
                        prefs.setInt(UserPrefs.OVERLAY_Y, ny)
                    }
                    Unit
                }
            ) { act -> handleAction(act) }
        } else {
            overlay.hide()
        }
    }

    /** 横竖屏切换后按新屏幕尺寸重建悬浮窗（用户反馈"横屏只能在左半屏动"） */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        latestCfg?.let { applyOverlay(it) }
    }

    private suspend fun foregroundLoop() {
        while (true) {
            val target = prefs.targetPackage.first()
            PlaybackEngine.targetPackage = target
            checkForeground(target)
            delay(500)
        }
    }

    /**
     * 前台检测**只更新状态灯，绝不打断演奏**。
     *
     * v1.5 的教训：原神是 Unity SurfaceView，window.root 恒为 null；游戏里弹个通知、
     * 划一下手势栏，WINDOW_STATE_CHANGED 就把 lastForeignPkg 刷成 systemui。
     * 于是「连续 3 次不在前台 → 急停」会在开播 0.8~1.5 秒内掐掉曲子，
     * 且 stop() 把进度清零 → 再点播放从头重演。用户看到的就是「弹一秒就停」。
     * 手势能注入成功本身就证明游戏在前台，不需要也不应该做二次确认。
     */
    private suspend fun checkForeground(target: String) {
        PlayerState.requireForeground(PlaybackEngine.isTargetForeground(target))
    }

    private fun handleAction(action: String) {
        scope.launch {
            when (action) {
                ACTION_PLAY -> doPlay()
                ACTION_PREV -> switchTrack(-1)
                ACTION_NEXT -> switchTrack(1)
                ACTION_PAUSE -> PlaybackEngine.pause()
                ACTION_STOP -> PlaybackEngine.stop("已急停")
                ACTION_CALIBRATE -> startCalibration()
                ACTION_TEST_TAP -> testFirstKey()
                ACTION_QUERY -> Unit
            }
        }
    }

    /**
     * 试弹一个 Q 键：把「手势注入」和「坐标是否正确」两件事分开验证。
     * 用户点一下就知道是 app 没发手势，还是坐标偏到别处去了。
     */
    private suspend fun testFirstKey() {
        if (PlaybackEngine.service == null) {
            PlayerState.toast("无障碍服务未连接")
            return
        }
        val overrides = withContext(Dispatchers.IO) { prefs.overrides.first() }
        val key = "Q"
        val pt = com.liquidlyre.app.data.KeyMap.pointOf(key, overrides)
        if (pt == null) { PlayerState.toast("Q 键坐标缺失"); return }
        val path = android.graphics.Path().apply { moveTo(pt.x, pt.y) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 70L)
            )
            .build()
        val ok = try {
            dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    PlayerState.diag("测试手势完成：Q 已点击")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    PlayerState.diag("测试手势被取消（游戏不在前台 / 该位置不可点）")
                }
            }, null)
        } catch (t: Throwable) {
            PlayerState.diag("测试手势异常：${t.message}")
            false
        }
        PlayerState.toast("测试点击 Q（${pt.x.toInt()} , ${pt.y.toInt()}）→ ${if (ok) "已发送" else "被拒绝"}")
    }

    /**
     * 打开全屏校准层。校准期间先收起悬浮窗，避免面板挡住琴键；
     * 保存或取消后自动恢复。用户切到游戏时校准层依然盖在游戏上（TYPE_ACCESSIBILITY_OVERLAY）。
     */
    private suspend fun startCalibration() {
        if (calibration?.isShowing() == true) {
            PlayerState.toast("校准层已打开")
            return
        }
        val initial = withContext(Dispatchers.IO) { prefs.overrides.first() }
        overlay.hide()
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val cal = CalibrationOverlay(
            context = this,
            wm = wm,
            initial = initial,
            onSave = { pts ->
                scope.launch {
                    withContext(Dispatchers.IO) { prefs.setOverrides(pts) }
                    PlayerState.toast("已保存 ${pts.size} 个琴键坐标，可以开弹了")
                    latestCfg?.let { applyOverlay(it) }
                }
                calibration = null
                Unit
            },
            onClose = {
                scope.launch {
                    latestCfg?.let { applyOverlay(it) }
                    PlayerState.toast("已取消校准")
                }
                calibration = null
                Unit
            }
        )
        calibration = cal
        cal.show()
        PlayerState.toast("把 21 个点拖到游戏琴键上，然后点「保存」")
    }

    /** 切歌：加载新曲后保持 PAUSED，不自动播放 */
    private suspend fun switchTrack(delta: Int) {
        if (PlayerState.snap.value.state == PlayState.PLAYING) PlaybackEngine.stop("切歌")
        val entries = withContext(Dispatchers.IO) { repo.allList() }
        if (entries.isEmpty()) { PlayerState.toast("曲库为空"); return }
        val curId = PlayerState.snap.value.current?.documentId
        val idx = entries.indexOfFirst { it.documentId == curId }
        val next = if (idx < 0) 0 else (idx + delta + entries.size) % entries.size
        val picked = entries[next]
        val parsed = withContext(Dispatchers.IO) { repo.loadScore(picked) }
        if (parsed == null) {
            PlayerState.toast("《${picked.name}》解析失败，原因见设置⑨")
            return
        }
        PlayerState.update {
            it.copy(
                state = PlayState.PAUSED, current = picked, parsed = parsed,
                durationMs = parsed.durationMs, progressMs = 0L, currentKeys = emptySet()
            )
        }
        PlayerState.toast(
            "已选：${parsed.name} · ${parsed.noteCount} 音 · ${parsed.durationMs / 1000}s（点▶）"
        )
    }

    /**
     * 播放/暂停切换 + 断点续播。
     *
     * v1.5 起：悬浮窗 ▶ = **真实弹奏**（注入手势进游戏）。
     * 之前这里先判 `isTargetForeground`，而原神是 Unity SurfaceView，`window.root`
     * 拿不到包名 → 永远判「不在前台」→ 降级成模拟音 → 游戏里一个音都不弹，
     * 用户点完只看到「已暂停」，自然说它「摆设」。
     *
     * 现在：无障碍服务活着就能弹；前台检测只用于状态灯与安全急停。
     */
    private suspend fun doPlay() {
        if (PlayerState.snap.value.state == PlayState.PLAYING) {
            PlaybackEngine.pause()
            return
        }
        if (PlaybackEngine.service == null) {
            PlayerState.toast("无障碍服务未连接：请到设置①重新开启")
            return
        }

        // 没选曲就自动加载曲库第一首 —— 悬浮窗上只有「上一首/下一首」，没有曲库，
        // 不自动选的话点 ▶ 永远只弹一句「请先选曲」，看起来就是摆设。
        var current = PlayerState.snap.value.parsed
        if (current == null) {
            val entries = withContext(Dispatchers.IO) { repo.allList() }
            if (entries.isEmpty()) {
                PlayerState.toast("曲库为空：请到设置③添加曲谱目录后刷新")
                return
            }
            val picked = entries.first()
            val loaded = withContext(Dispatchers.IO) { repo.loadScore(picked) }
            if (loaded == null) {
                PlayerState.toast("《${picked.name}》解析失败，请检查谱子格式")
                return
            }
            current = loaded
            val auto = loaded
            PlayerState.update {
                it.copy(
                    current = picked, parsed = auto,
                    durationMs = auto.durationMs, progressMs = 0L
                )
            }
            PlayerState.toast("已自动选曲：${auto.name}")
        }
        val score = current

        val overrides = withContext(Dispatchers.IO) { prefs.overrides.first() }
        val withAudio = prefs.simulateAudio.first()
        // 播完后再点 ▶ 从头开始；否则 progressMs==durationMs 会让索引越界，瞬间「演奏完成」
        val snapNow = PlayerState.snap.value
        val resumeFrom = if (snapNow.durationMs > 0L && snapNow.progressMs >= snapNow.durationMs - 500L) {
            0L
        } else snapNow.progressMs
        PlaybackEngine.play(
            score, overrides, inject = true, withAudio = withAudio, startFromMs = resumeFrom
        )
        PlayerState.toast(
            (if (withAudio) "真实弹奏 + 本地钢琴音（${score.noteCount} 音 / ${score.durationMs / 1000}s）"
            else "真实弹奏：正在注入手势（${score.noteCount} 音 / ${score.durationMs / 1000}s）") +
                if (overrides.isEmpty()) " · 未校准坐标（设置⑧）" else ""
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Unity/SurfaceView 游戏拿不到 window.root 包名，只能靠事件流记住前台 App。
        // 滤掉系统 UI：通知栏/手势导航会把它顶成「非游戏」，状态灯乱跳。
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg !in SYSTEM_PKGS) {
            PlaybackEngine.noteForegroundPackage(pkg)
        }
        scope.launch { checkForeground(PlaybackEngine.targetPackage) }
    }

    override fun onInterrupt() { PlaybackEngine.stop("服务中断，演奏已急停") }

    override fun onDestroy() {
        PlayerState.requireA11y(false)
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        overlay.hide()
        PlaybackEngine.detach()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.liquidlyre.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.liquidlyre.app.ACTION_PAUSE"
        const val ACTION_STOP = "com.liquidlyre.app.ACTION_STOP"
        const val ACTION_PREV = "com.liquidlyre.app.ACTION_PREV"
        const val ACTION_NEXT = "com.liquidlyre.app.ACTION_NEXT"
        const val ACTION_QUERY = "com.liquidlyre.app.ACTION_QUERY"
        const val ACTION_CALIBRATE = "com.liquidlyre.app.ACTION_CALIBRATE"
        const val ACTION_TEST_TAP = "com.liquidlyre.app.ACTION_TEST_TAP"

        /** 这些包名出现不代表用户离开游戏，只用于状态灯过滤 */
        val SYSTEM_PKGS = setOf(
            "com.android.systemui", "android", "com.android.launcher3",
            "com.android.settings", "com.miui.home", "com.huawei.android.launcher",
            "com.coloros.launcher", "com.vivo.launcher", "com.oppo.launcher"
        )
    }
}
