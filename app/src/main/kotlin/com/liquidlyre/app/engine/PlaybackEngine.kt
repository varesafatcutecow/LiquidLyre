package com.liquidlyre.app.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import com.liquidlyre.app.data.KeyMap
import com.liquidlyre.app.data.ParsedScore
import com.liquidlyre.app.data.TimelineEvent
import com.liquidlyre.app.data.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 高精度播放引擎（进程单例）：主 App 与无障碍服务共用。
 *
 * 两种输出可独立开关：
 *  - inject=true  → dispatchGesture 往游戏里注入坐标点击（真实弹奏）
 *  - withAudio=true → AudioSynth 本地发声（试听；也用于「边弹边响」）
 *
 * v1.4 的两个致命 bug 在这里修掉：
 *  1) `StrokeDescription(path, offset, hold, willContinue=true)` —— willContinue=true 表示
 *     这根手指**不自动抬起**，必须 continueStroke 续接。我们从没续接，于是第一根手指
 *     永远按住，系统把后续每一个 GestureDescription 都判为冲突 → onCancelled → 急停。
 *     游戏里一个音都没弹出，用户看到的就是「点了播放，一秒回到已急停，纯摆设」。
 *     现在用 3 参构造（willContinue 默认 false），按完即抬。
 *  2) onCancelled 一律 stopInternal —— 用户手指碰一下屏幕、或前一个手势还在收尾，
 *     都会取消当前手势。演奏中途被自己急停，表现为「按了没用」。现在只记诊断，
 *     连续失败 5 次才停。
 *
 * 手势串行：一个 DOWN 事件 = 一个 GestureDescription（和弦多 stroke 同批按下），
 * 用 gestureBusyUntil 保证上一批执行完再发下一批，绝不互相取消。
 */
object PlaybackEngine {
    @Volatile var service: AccessibilityService? = null
    @Volatile var prefs: UserPrefs? = null
    @Volatile var targetPackage: String = UserPrefs.DEFAULT_PACKAGE

    private var scope: CoroutineScope? = null
    private var playJob: Job? = null
    @Volatile private var cancelled = false

    /** 上一次手势预计结束时刻（uptimeMillis），用于串行化 */
    @Volatile private var gestureBusyUntil = 0L
    @Volatile private var gestureFailStreak = 0
    @Volatile private var gestureSentTotal = 0
    @Volatile private var audioNoteSent = 0
    @Volatile private var warnedNoCalibration = false

    /** 最近一次「非本应用」的窗口包名（游戏 SurfaceView 拿不到 root，只能靠事件流兜底） */
    @Volatile private var lastForeignPkg: String? = null
    @Volatile private var lastForeignAt = 0L

    fun attach(svc: AccessibilityService, prefs: UserPrefs, scope: CoroutineScope) {
        this.service = svc
        this.prefs = prefs
        this.scope = scope
    }

    fun detach() {
        stopInternal("服务已关闭", silent = true)
        service = null
        scope = null
        prefs = null
    }

    fun isPlaying(): Boolean = playJob?.isActive == true && !cancelled

    fun stop(reason: String) = stopInternal(reason)

    private fun stopInternal(reason: String, silent: Boolean = false) {
        cancelled = true
        playJob?.cancel()
        playJob = null
        AudioSynth.silence()
        PlayerState.update { it.copy(state = PlayState.STOPPED, progressMs = 0L, currentKeys = emptySet()) }
        if (!silent) PlayerState.toast(reason)
    }

    fun pause() {
        cancelled = true
        playJob?.cancel()
        playJob = null
        AudioSynth.silence()
        PlayerState.update { it.copy(state = PlayState.PAUSED, currentKeys = emptySet()) }
    }

    /**
     * 开播。
     * @param inject 真实弹奏：往游戏注入手势（需要无障碍服务已连接 + 已校准坐标）
     * @param withAudio 同时本地发声（试听 / 边弹边响）
     * @param externalScope 无服务时驱动协程（纯发声模式用 viewModelScope）
     */
    fun play(
        score: ParsedScore,
        overrides: Map<String, PointF>,
        inject: Boolean = false,
        withAudio: Boolean = true,
        externalScope: CoroutineScope? = null,
        startFromMs: Long = 0L
    ) {
        if (inject && (service == null || scope == null)) {
            PlayerState.toast("无障碍服务未连接，无法真实弹奏")
            return
        }
        val sc = if (inject) scope!! else (externalScope ?: scope) ?: return
        playJob?.cancel()
        cancelled = false
        gestureFailStreak = 0
        gestureBusyUntil = 0L
        gestureSentTotal = 0
        audioNoteSent = 0

        playJob = sc.launch(Dispatchers.Default) {
            val pf = prefs
            val speed = (pf?.speedComp?.first() ?: 1f).coerceIn(0.6f, 1.4f)
            val spread = pf?.chordSpread?.first() ?: 1
            PlayerState.update {
                it.copy(
                    state = PlayState.PLAYING, parsed = score,
                    durationMs = score.durationMs, progressMs = startFromMs, currentKeys = emptySet()
                )
            }
            PlayerState.diag("开播 v1.51：${score.name} · ${score.source} · ${score.events.size} 事件 · ${score.durationMs / 1000}s")
            PlayerState.diag("前台检测已降级为状态灯：演奏过程不会被窗口事件打断")

            // 未校准提示：默认坐标是按 iQOO 15 3168×1440 推的，换机必偏
            if (inject && overrides.isEmpty() && !warnedNoCalibration) {
                warnedNoCalibration = true
                PlayerState.diag("未校准琴键坐标：默认按 3168×1440 推算，位置可能偏移")
            }

            val events = score.events
            var idx = events.indexOfFirst { it.timeMs >= startFromMs }
            if (idx < 0) idx = events.size
            val baseMs = if (idx < events.size) events[idx].timeMs else 0L
            val startNs = System.nanoTime()
            val held = HashSet<String>()

            while (idx < events.size && !cancelled) {
                val ev = events[idx]
                val targetNs = startNs + ((ev.timeMs - baseMs) / speed).toLong() * 1_000_000L
                val waitNs = targetNs - System.nanoTime()
                if (waitNs > 0) delay(waitNs / 1_000_000L)
                if (cancelled) break

                val jitterMs = abs((System.nanoTime() - targetNs) / 1_000_000f)
                if (jitterMs > 40f && idx % 8 == 0) {
                    PlayerState.diag("抖动 ${"%.1f".format(jitterMs)}ms @ ${ev.timeMs}ms")
                }

                when (ev.action) {
                    TimelineEvent.Action.DOWN -> {
                        held.addAll(ev.keys)
                        PlayerState.update { it.copy(currentKeys = held.toSet(), progressMs = ev.timeMs) }
                        if (withAudio) {
                            // 延音按「到这个键真正抬起的时长」算，而不是到下一个 DOWN 的间隔。
                            // 后者在长音 / 和弦谱里会把音切碎，听感像乱按。
                            AudioSynth.notesOn(ev.keys, sustainMsForKey(events, idx, speed))
                            audioNoteSent += ev.keys.size
                            if (audioNoteSent == ev.keys.size) {
                                PlayerState.diag("首个音已送入合成器：${ev.keys.joinToString("")} · ${AudioSynth.selfTestShort()}")
                            }
                        }
                        if (inject) {
                            dispatchChord(ev.keys, overrides, spread)
                        }
                    }
                    TimelineEvent.Action.UP -> {
                        held.removeAll(ev.keys)
                        PlayerState.update { it.copy(currentKeys = held.toSet()) }
                    }
                }
                idx++
            }
            if (!cancelled) {
                PlayerState.update {
                    it.copy(state = PlayState.PAUSED, progressMs = score.durationMs, currentKeys = emptySet())
                }
                PlayerState.diag("试听完成：送入合成器 $audioNoteSent 音 · 手势 $gestureSentTotal 批")
                PlayerState.toast("演奏完成")
            }
        }
    }

    /** 某键从本次按下到同键抬起的时长（ms），用于合成器延音 */
    private fun sustainMsForKey(events: List<TimelineEvent>, idx: Int, speed: Float): Int {
        val keys = events[idx].keys.toSet()
        val t = events[idx].timeMs
        var j = idx + 1
        while (j < events.size && j < idx + 64) {
            val e = events[j]
            if (e.action == TimelineEvent.Action.UP && e.keys.any { it in keys }) {
                val dur = ((e.timeMs - t) / speed).toInt()
                return (dur * 1.45f).toInt().coerceIn(160, 2400)
            }
            j++
        }
        return 420
    }

    /**
     * 一个 DOWN 事件 = 一次手势。和弦的多个键放进同一个 GestureDescription，
     * 用 spread 错开 1~15ms，模拟"一把抓下去"而不是一根手指点 21 下。
     *
     * 严格串行：dispatch 前等到 gestureBusyUntil，避免与上一批互相取消。
     */
    private suspend fun dispatchChord(keys: List<String>, overrides: Map<String, PointF>, spreadMs: Int) {
        val svc = service ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val pts = keys.mapNotNull { k -> KeyMap.pointOf(k, overrides)?.let { k to it } }
        if (pts.isEmpty()) {
            PlayerState.diag("跳过：${keys.joinToString("")} 无坐标")
            return
        }

        // 等到上一批手势结束（最多等 200ms，避免极端情况下卡死）
        val wait = gestureBusyUntil - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait.coerceAtMost(200))

        // 和弦键多时压缩 spread，保证整批不超过 36ms（最小音符间隔约 39ms，不能压到下一批）
        val maxSpread = if (pts.size <= 1) 0 else ((36 - HOLD_MS) / (pts.size - 1)).coerceAtLeast(1).toInt()
        val spread = spreadMs.coerceIn(1, maxSpread.coerceAtLeast(1)).toLong()

        val builder = GestureDescription.Builder()
        pts.forEachIndexed { i, (_, pt) ->
            val path = Path().apply { moveTo(pt.x, pt.y) }
            // 3 参构造：willContinue 默认 false，按完自动抬起。绝不能用 4 参 true。
            builder.addStroke(GestureDescription.StrokeDescription(path, i * spread, HOLD_MS))
        }
        val totalMs = (pts.size - 1) * spread + HOLD_MS
        val gesture = builder.build()

        // +10ms 覆盖系统手势派发延迟，避免下一批在上一批收尾时发出被取消
        gestureBusyUntil = SystemClock.uptimeMillis() + totalMs + 10
        val ok = try {
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCancelled(g: GestureDescription?) {
                    // 用户碰屏幕 / 系统弹窗 / 游戏切后台都会取消当前手势。
                    // **绝不自动急停**：手感问题应该让用户自己判断，自动停只会让"看起来是摆设"。
                    gestureFailStreak++
                    if (gestureFailStreak == 3 || gestureFailStreak == 10) {
                        PlayerState.diag("手势被取消 ${gestureFailStreak} 次（触屏/弹窗/切后台都会取消，不影响后续）")
                    }
                }

                override fun onCompleted(g: GestureDescription?) {
                    gestureFailStreak = 0
                }
            }, null)
        } catch (t: Throwable) {
            PlayerState.diag("dispatchGesture 异常：${t.javaClass.simpleName} ${t.message ?: ""}")
            false
        }
        if (ok) {
            gestureSentTotal++
            if (gestureSentTotal == 1) PlayerState.diag("首帧手势已注入")
        } else {
            gestureFailStreak++
            PlayerState.diag("dispatchGesture 返回 false（服务连接异常）")
        }
    }

    /**
     * 前台检测。原神这类 SurfaceView 游戏 `window.root` 常常为 null，
     * 所以以「最近一次窗口状态事件的包名」为主，windows 扫描为辅。
     * 注意：这个值只用于状态灯/提示，**不再作为能否演奏的硬闸门**。
     */
    fun isTargetForeground(target: String): Boolean {
        // 一旦收到过目标包名的窗口事件，就认为在前台，直到收到别的 App 的事件。
        // **不能加超时**：Unity 游戏播放时不会持续发窗口事件，超时会把正在进行的演奏误杀。
        if (lastForeignPkg == target) return true
        val svc = service ?: return false
        val windows = svc.windows ?: return false
        for (w in windows) {
            val pkg = w.root?.packageName?.toString()
            if (pkg.isNullOrBlank()) continue
            if (pkg == svc.packageName) continue
            if (pkg == target) return true
        }
        return false
    }

    /** 无障碍事件里喂进来的前台包名（游戏窗口唯一可靠的信号） */
    fun noteForegroundPackage(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        val self = service?.packageName
        if (pkg == self) return
        if (pkg == lastForeignPkg) {
            lastForeignAt = SystemClock.elapsedRealtime()
        } else {
            lastForeignPkg = pkg
            lastForeignAt = SystemClock.elapsedRealtime()
        }
    }

    fun lastSeenPackage(): String? = lastForeignPkg

    private const val HOLD_MS = 28L
}
