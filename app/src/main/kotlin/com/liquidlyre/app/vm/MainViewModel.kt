package com.liquidlyre.app.vm

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.liquidlyre.app.data.LibrarySort
import com.liquidlyre.app.data.ScoreIndexEntity
import com.liquidlyre.app.data.ScoreRepository
import com.liquidlyre.app.data.UserPrefs
import com.liquidlyre.app.engine.PlayState
import com.liquidlyre.app.engine.PlaybackEngine
import com.liquidlyre.app.engine.PlayerState
import com.liquidlyre.app.service.LyreAccessibilityService
import com.liquidlyre.app.service.KeepAliveService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UiState(
    val tab: Int = 0,
    val query: String = "",
    val scanning: Boolean = false,
    val sortMode: String = LibrarySort.NAME,
    val targetPackage: String = UserPrefs.DEFAULT_PACKAGE,
    val perfMode: Boolean = false,
    val cacheFast: Boolean = false,
    val speedComp: Float = 1f,
    val chordSpread: Int = 1,
    val overlayW: Int = UserPrefs.DEFAULT_OVERLAY_W,
    val overlayH: Int = UserPrefs.DEFAULT_OVERLAY_H,
    val overlayX: Int = UserPrefs.DEFAULT_OVERLAY_X,
    val overlayY: Int = UserPrefs.DEFAULT_OVERLAY_Y,
    val overlayDraggable: Boolean = false,
    val overlayBlur: Float = 16f,
    val overlayEnabled: Boolean = true,
    val simulateAudio: Boolean = true,
    // 液态玻璃四滑杆（默认满配）
    val glassBlur: Float = 32f,
    val glassHeight: Float = 1f,
    val glassAmount: Float = 1f,
    val glassChroma: Float = 1f,
    val disclaimer: Boolean = false,
    val treeDirs: List<ScoreRepository.TreeDir> = emptyList()
)

class MainViewModel(private val app: Context) : ViewModel() {
    val repo = ScoreRepository(app)
    val prefs = UserPrefs(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val player = PlayerState.snap

    init {
        viewModelScope.launch {
            prefs.targetPackage.collect { _ui.value = _ui.value.copy(targetPackage = it) }
        }
        viewModelScope.launch { prefs.perfMode.collect { _ui.value = _ui.value.copy(perfMode = it) } }
        viewModelScope.launch { prefs.cacheFast.collect { _ui.value = _ui.value.copy(cacheFast = it) } }
        viewModelScope.launch { prefs.speedComp.collect { _ui.value = _ui.value.copy(speedComp = it) } }
        viewModelScope.launch { prefs.chordSpread.collect { _ui.value = _ui.value.copy(chordSpread = it) } }
        viewModelScope.launch { prefs.overlayW.collect { _ui.value = _ui.value.copy(overlayW = it) } }
        viewModelScope.launch { prefs.overlayH.collect { _ui.value = _ui.value.copy(overlayH = it) } }
        viewModelScope.launch { prefs.overlayX.collect { _ui.value = _ui.value.copy(overlayX = it) } }
        viewModelScope.launch { prefs.overlayY.collect { _ui.value = _ui.value.copy(overlayY = it) } }
        viewModelScope.launch { prefs.overlayDraggable.collect { _ui.value = _ui.value.copy(overlayDraggable = it) } }
        viewModelScope.launch { prefs.overlayBlur.collect { _ui.value = _ui.value.copy(overlayBlur = it) } }
        viewModelScope.launch { prefs.overlayEnabled.collect { _ui.value = _ui.value.copy(overlayEnabled = it) } }
        viewModelScope.launch { prefs.simulateAudio.collect { _ui.value = _ui.value.copy(simulateAudio = it) } }
        viewModelScope.launch { prefs.sortMode.collect { _ui.value = _ui.value.copy(sortMode = it) } }
        viewModelScope.launch { prefs.glassBlur.collect { _ui.value = _ui.value.copy(glassBlur = it) } }
        viewModelScope.launch { prefs.glassHeight.collect { _ui.value = _ui.value.copy(glassHeight = it) } }
        viewModelScope.launch { prefs.glassAmount.collect { _ui.value = _ui.value.copy(glassAmount = it) } }
        viewModelScope.launch { prefs.glassChroma.collect { _ui.value = _ui.value.copy(glassChroma = it) } }
        viewModelScope.launch { prefs.disclaimerAccepted.collect { _ui.value = _ui.value.copy(disclaimer = it) } }
        refreshTrees()
    }

    fun setTab(tab: Int) { _ui.value = _ui.value.copy(tab = tab) }
    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }
    fun setSortMode(mode: String) { viewModelScope.launch { prefs.setString(UserPrefs.SORT_MODE, mode) } }

    fun refreshTrees() {
        _ui.value = _ui.value.copy(treeDirs = repo.listTrees())
    }

    fun addTreeUri(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {}
        viewModelScope.launch {
            prefs.addTreeUri(uri.toString())
            refreshTrees()
            scanLibrary()
        }
    }

    fun removeTreeUri(uri: String) {
        viewModelScope.launch {
            try { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            prefs.removeTreeUri(uri)
            repo.deleteTree(uri)
            refreshTrees()
        }
    }

    fun pinTree(uri: String) {
        viewModelScope.launch { prefs.pinTreeUri(uri); refreshTrees() }
    }

    fun scanLibrary() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(scanning = true)
            val r = repo.scanAll()
            r.errors.forEach { PlayerState.diag(it) }
            PlayerState.toast("曲库已更新：${r.added} 首${if (r.errors.isNotEmpty()) "（${r.errors.size} 个错误已记日志）" else ""}")
            _ui.value = _ui.value.copy(scanning = false)
        }
    }

    fun selectScore(entry: ScoreIndexEntity) {
        viewModelScope.launch {
            val parsed = repo.loadScore(entry)
            if (parsed == null) {
                PlayerState.toast("《${entry.name}》解析失败，原因见设置⑨诊断日志")
                return@launch
            }
            PlayerState.update {
                it.copy(
                    state = PlayState.PAUSED, current = entry, parsed = parsed,
                    durationMs = parsed.durationMs, progressMs = 0L, currentKeys = emptySet()
                )
            }
            PlayerState.toast("已选：${parsed.name} · ${formatSource(parsed)} · ${mmss(parsed.durationMs)}")
        }
    }

    companion object {
        fun formatSource(p: com.liquidlyre.app.data.ParsedScore): String = when (p.source) {
            "midi" -> "谐律键琴 MIDI ${p.noteCount}音"
            "cache" -> "高速缓存 ${p.noteCount}音"
            "yuanqin" -> "原琴谱 ${p.noteCount}音"
            else -> "BGI 键盘谱 ${p.noteCount}音"
        }

        fun mmss(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(total / 60, total % 60)
        }
    }

    fun play() {
        viewModelScope.launch {
            // 正在播 → 再点就是暂停（否则用户点第二下看不出反应）
            if (player.value.state == PlayState.PLAYING) {
                PlaybackEngine.pause()
                return@launch
            }
            val p = ui.value.targetPackage
            PlaybackEngine.targetPackage = p
            val parsed = player.value.parsed ?: run { PlayerState.toast("请先选曲"); return@launch }
            val overrides = prefs.overrides.first()
            // 播完后再点 ▶ 从头开始
            val st = player.value
            val resumeFrom = if (st.durationMs > 0L && st.progressMs >= st.durationMs - 500L) 0L else st.progressMs
            // 演奏台 = 试听：只发声、不注入手势（游戏不在前台，注入了也弹不到）
            PlaybackEngine.play(
                parsed, overrides, inject = false, withAudio = true,
                externalScope = viewModelScope, startFromMs = resumeFrom
            )
            PlayerState.toast("演示中：本地钢琴音（真实弹奏请用悬浮窗 ▶）")
            KeepAliveService.start(app)
        }
    }

    fun setSimulateAudio(v: Boolean) { viewModelScope.launch { prefs.setBool(UserPrefs.SIMULATE_AUDIO, v) } }

    /** 演奏台「🔊 测试音」：不依赖选曲/播放引擎，直接验证 AudioTrack 能否发声 */
    fun testTone() {
        val msg = com.liquidlyre.app.engine.AudioSynth.selfTest()
        PlayerState.toast(msg)
    }

    fun pause() = PlaybackEngine.pause()
    fun stop() = PlaybackEngine.stop("已急停")

    // 设置类先即时更新内存 UiState（滑杆跟手不卡），再异步落库持久化。
    // 关键：不能等 DataStore 磁盘写→Flow 回 emit 才刷新 UI，否则每帧拖动增量丢失。
    /** 目标包名输入框：即时内存 + 异步落库（输入场景每字符写库由 DataStore 合并，可接受） */
    fun targetPkg(v: String) {
        _ui.value = _ui.value.copy(targetPackage = v)
        viewModelScope.launch { prefs.setString(UserPrefs.TARGET_PACKAGE, v) }
    }
    fun setPerf(v: Boolean) {
        _ui.value = _ui.value.copy(perfMode = v)
        viewModelScope.launch { prefs.setBool(UserPrefs.PERF_MODE, v) }
    }
    fun setCacheFast(v: Boolean) {
        _ui.value = _ui.value.copy(cacheFast = v)
        viewModelScope.launch { prefs.setBool(UserPrefs.CACHE_FAST, v); scanLibrary() }
    }
    fun setOverlayDraggable(v: Boolean) {
        _ui.value = _ui.value.copy(overlayDraggable = v)
        viewModelScope.launch { prefs.setBool(UserPrefs.OVERLAY_DRAGGABLE, v) }
    }
    fun setOverlayEnabled(v: Boolean) {
        _ui.value = _ui.value.copy(overlayEnabled = v)
        viewModelScope.launch { prefs.setBool(UserPrefs.OVERLAY_ENABLED, v) }
    }

    // ===== 滑杆专用：live 只更新内存（拖动每帧调用，零磁盘 I/O）；commit 松手时落库一次 =====
    fun speedLive(v: Float) { _ui.value = _ui.value.copy(speedComp = v) }
    fun commitSpeed() { viewModelScope.launch { prefs.setFloat(UserPrefs.SPEED_COMP, _ui.value.speedComp) } }

    fun chordSpreadLive(v: Int) { _ui.value = _ui.value.copy(chordSpread = v) }
    fun commitChordSpread() { viewModelScope.launch { prefs.setInt(UserPrefs.CHORD_SPREAD, _ui.value.chordSpread) } }

    fun overlayBlurLive(v: Float) { _ui.value = _ui.value.copy(overlayBlur = v) }
    fun commitOverlayBlur() { viewModelScope.launch { prefs.setFloat(UserPrefs.OVERLAY_BLUR, _ui.value.overlayBlur) } }

    // 悬浮窗几何：不再用写死的 MAX_OVERLAY_BOTTOM（700px 是竖屏思维，横屏下会把窗口锁在左半屏）。
    // 真正的边界由服务侧按当前屏幕实时 clamp，这里只保证非负与合理区间。
    fun overlaySizeLive(w: Int, h: Int) {
        _ui.value = _ui.value.copy(
            overlayW = w.coerceIn(320, 2400),
            overlayH = h.coerceIn(200, 1400)
        )
    }
    fun commitOverlaySize() {
        val s = _ui.value
        viewModelScope.launch { prefs.setInt(UserPrefs.OVERLAY_W, s.overlayW); prefs.setInt(UserPrefs.OVERLAY_H, s.overlayH) }
    }

    fun overlayPosLive(x: Int, y: Int) {
        _ui.value = _ui.value.copy(
            overlayX = x.coerceIn(0, 4000),
            overlayY = y.coerceIn(0, 4000)
        )
    }
    fun commitOverlayPos() {
        val s = _ui.value
        viewModelScope.launch { prefs.setInt(UserPrefs.OVERLAY_X, s.overlayX); prefs.setInt(UserPrefs.OVERLAY_Y, s.overlayY) }
    }

    fun glassBlurLive(v: Float) { _ui.value = _ui.value.copy(glassBlur = v) }
    fun commitGlassBlur() { viewModelScope.launch { prefs.setFloat(UserPrefs.GLASS_BLUR, _ui.value.glassBlur) } }
    fun glassHeightLive(v: Float) { _ui.value = _ui.value.copy(glassHeight = v) }
    fun commitGlassHeight() { viewModelScope.launch { prefs.setFloat(UserPrefs.GLASS_HEIGHT, _ui.value.glassHeight) } }
    fun glassAmountLive(v: Float) { _ui.value = _ui.value.copy(glassAmount = v) }
    fun commitGlassAmount() { viewModelScope.launch { prefs.setFloat(UserPrefs.GLASS_AMOUNT, _ui.value.glassAmount) } }
    fun glassChromaLive(v: Float) { _ui.value = _ui.value.copy(glassChroma = v) }
    fun commitGlassChroma() { viewModelScope.launch { prefs.setFloat(UserPrefs.GLASS_CHROMA, _ui.value.glassChroma) } }
    fun acceptDisclaimer() { viewModelScope.launch { prefs.setBool(UserPrefs.DISCLAIMER_ACCEPTED, true) } }
    fun star(entry: ScoreIndexEntity) { viewModelScope.launch { repo.setStarred(entry.documentId, !entry.starred) } }
    fun clearToast() = PlayerState.clearToast()

    fun saveCalibration(key: String, point: android.graphics.PointF) {
        viewModelScope.launch { prefs.setOverride(key, point) }
    }
    fun clearCalibration() { viewModelScope.launch { prefs.clearOverrides() } }

    // ===== 自定义底图（文件真相：选图复制进内部，重启不失效） =====
    private val _bgRevision = MutableStateFlow(0)
    val bgRevision: StateFlow<Int> = _bgRevision.asStateFlow()

    fun importWallpaper(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { com.liquidlyre.app.util.WallpaperStore.save(app, uri) }
            if (ok) {
                _bgRevision.value++
                PlayerState.toast("底图已应用")
            } else {
                PlayerState.toast("底图读取失败，请换一张")
            }
        }
    }

    fun resetWallpaper() {
        com.liquidlyre.app.util.WallpaperStore.clear(app)
        _bgRevision.value++
        PlayerState.toast("已恢复默认底图")
    }

    class Factory(private val app: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app.applicationContext) as T
    }
}
