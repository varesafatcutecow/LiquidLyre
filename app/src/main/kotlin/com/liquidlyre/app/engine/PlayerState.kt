package com.liquidlyre.app.engine

import android.content.Context
import java.io.File
import com.liquidlyre.app.data.ParsedScore
import com.liquidlyre.app.data.ScoreIndexEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayState { IDLE, PAUSED, PLAYING, STOPPED, ERROR }

data class PlayerSnapshot(
    val state: PlayState = PlayState.IDLE,
    val current: ScoreIndexEntity? = null,
    val parsed: ParsedScore? = null,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentKeys: Set<String> = emptySet(),
    val a11yOn: Boolean = false,
    val gameForeground: Boolean = false,
    val message: String? = null,
    val messageKey: Int = 0,
    val diag: List<String> = emptyList()
)

/**
 * 全局播放状态机：主 App、无障碍服务、悬浮窗通过 BroadcastReceiver 汇入同一单例。
 * 单进程内共享 StateFlow，避免跨进程序列化复杂度；悬浮窗点击发广播 → Receiver 调用这里。
 */
object PlayerState {
    private val _snap = MutableStateFlow(PlayerSnapshot())
    val snap: StateFlow<PlayerSnapshot> = _snap.asStateFlow()
    private var msgKey = 0

    fun update(block: (PlayerSnapshot) -> PlayerSnapshot) { _snap.value = block(_snap.value) }

    fun toast(text: String) {
        msgKey++
        update { it.copy(message = text, messageKey = msgKey) }
    }

    fun clearToast() { update { it.copy(message = null) } }

    fun diag(line: String) {
        update { s -> s.copy(diag = (listOf(line) + s.diag).take(200)) }
    }

    /** 上次未捕获崩溃的完整堆栈（从磁盘读入），供设置⑨展示 */
    @Volatile var lastCrash: String? = null
        private set

    fun reloadCrash(context: Context) {
        lastCrash = try {
            val f = File(File(context.filesDir, "crash"), "last_crash.txt")
            if (f.exists()) f.readText().take(6000) else null
        } catch (_: Throwable) { null }
    }

    fun clearCrash(context: Context) {
        lastCrash = null
        try { File(File(context.filesDir, "crash"), "last_crash.txt").delete() } catch (_: Throwable) {}
    }

    fun requireA11y(on: Boolean) { update { it.copy(a11yOn = on) } }
    fun requireForeground(on: Boolean) { update { it.copy(gameForeground = on) } }
}
