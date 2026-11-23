package com.liquidlyre.app.data

import android.content.Context
import android.graphics.PointF
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyre_prefs")

/** 全部设置集中在 DataStore；坐标校准与目录授权重启后仍有效 */
class UserPrefs(private val context: Context) {

    private val ds = context.applicationContext.dataStore

    companion object {
        val TARGET_PACKAGE = stringPreferencesKey("target_package")
        val OVERLAY_W = intPreferencesKey("overlay_w")
        val OVERLAY_H = intPreferencesKey("overlay_h")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val OVERLAY_DRAGGABLE = booleanPreferencesKey("overlay_draggable")
        val PERF_MODE = booleanPreferencesKey("perf_mode")
        val OVERLAY_BLUR = floatPreferencesKey("overlay_blur")
        val SPEED_COMP = floatPreferencesKey("speed_comp")
        val CHORD_SPREAD = intPreferencesKey("chord_spread")
        val CACHE_FAST = booleanPreferencesKey("cache_fast")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")   // 悬浮窗总开关
        val SIMULATE_AUDIO = booleanPreferencesKey("simulate_audio")     // 演奏台模拟音效
        // 液态玻璃四滑杆（官方 GlassPlayground 四参数，用户实时可调）
        val GLASS_BLUR = floatPreferencesKey("glass_blur")       // 模糊半径 dp 0-32
        val GLASS_HEIGHT = floatPreferencesKey("glass_height")   // 折射高度系数 0-1
        val GLASS_AMOUNT = floatPreferencesKey("glass_amount")   // 折射强度系数 0-1
        val GLASS_CHROMA = floatPreferencesKey("glass_chroma")   // 色散 0/1
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val TREE_URIS = stringPreferencesKey("tree_uris_ordered") // 换行分隔，保证顺序
        val OVERRIDE_KEYS = stringSetPreferencesKey("override_keys") // "Q:748.0,758.0"
        val SCORE_INDEX = stringPreferencesKey("score_index_json") // 曲库索引 JSON
        val SORT_MODE = stringPreferencesKey("library_sort_mode")  // 曲库排序模式

        const val DEFAULT_PACKAGE = "com.miHoYo.Yuanshen"
        const val DEFAULT_OVERLAY_W = 1000
        const val DEFAULT_OVERLAY_H = 575      // 与宽度成 1.74:1，贴合参考图
        const val DEFAULT_OVERLAY_X = 1084
        const val DEFAULT_OVERLAY_Y = 60
    }

    val targetPackage: Flow<String> = ds.data.map { it[TARGET_PACKAGE] ?: DEFAULT_PACKAGE }
    val overlayW: Flow<Int> = ds.data.map { it[OVERLAY_W] ?: DEFAULT_OVERLAY_W }
    val overlayH: Flow<Int> = ds.data.map { it[OVERLAY_H] ?: DEFAULT_OVERLAY_H }
    val overlayX: Flow<Int> = ds.data.map { it[OVERLAY_X] ?: DEFAULT_OVERLAY_X }
    val overlayY: Flow<Int> = ds.data.map { it[OVERLAY_Y] ?: DEFAULT_OVERLAY_Y }
    val overlayDraggable: Flow<Boolean> = ds.data.map { it[OVERLAY_DRAGGABLE] ?: false }
    val perfMode: Flow<Boolean> = ds.data.map { it[PERF_MODE] ?: false }
    val overlayBlur: Flow<Float> = ds.data.map { it[OVERLAY_BLUR] ?: 16f }
    val speedComp: Flow<Float> = ds.data.map { it[SPEED_COMP] ?: 1f }
    val chordSpread: Flow<Int> = ds.data.map { it[CHORD_SPREAD] ?: 1 }
    val cacheFast: Flow<Boolean> = ds.data.map { it[CACHE_FAST] ?: false }
    val overlayEnabled: Flow<Boolean> = ds.data.map { it[OVERLAY_ENABLED] ?: true }
    val simulateAudio: Flow<Boolean> = ds.data.map { it[SIMULATE_AUDIO] ?: true }
    val sortMode: Flow<String> = ds.data.map { it[SORT_MODE] ?: LibrarySort.NAME }
    // 玻璃四参数（默认满配：官方 GlassPlayground 四滑杆上界）
    val glassBlur: Flow<Float> = ds.data.map { it[GLASS_BLUR] ?: 32f }
    val glassHeight: Flow<Float> = ds.data.map { it[GLASS_HEIGHT] ?: 1f }
    val glassAmount: Flow<Float> = ds.data.map { it[GLASS_AMOUNT] ?: 1f }
    val glassChroma: Flow<Float> = ds.data.map { it[GLASS_CHROMA] ?: 1f }
    val disclaimerAccepted: Flow<Boolean> = ds.data.map { it[DISCLAIMER_ACCEPTED] ?: false }
    val treeUris: Flow<List<String>> = ds.data.map { raw ->
        raw[TREE_URIS]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }
    val overrides: Flow<Map<String, PointF>> = ds.data.map { prefs ->
        (prefs[OVERRIDE_KEYS] ?: emptySet()).mapNotNull { s ->
            val parts = s.split(":")
            if (parts.size == 3) {
                val xy = parts[2].split(",")
                if (xy.size == 2) {
                    val x = xy[0].toFloatOrNull(); val y = xy[1].toFloatOrNull()
                    if (x != null && y != null) parts[0] to PointF(x, y) else null
                } else null
            } else null
        }.toMap()
    }

    val rawIndex: Flow<String> = ds.data.map { it[SCORE_INDEX] ?: "[]" }

    suspend fun setIndex(json: String) = ds.edit { it[SCORE_INDEX] = json }

    suspend fun setString(key: Preferences.Key<String>, value: String) = ds.edit { it[key] = value }
    suspend fun setInt(key: Preferences.Key<Int>, value: Int) = ds.edit { it[key] = value }
    suspend fun setFloat(key: Preferences.Key<Float>, value: Float) = ds.edit { it[key] = value }
    suspend fun setBool(key: Preferences.Key<Boolean>, value: Boolean) = ds.edit { it[key] = value }

    suspend fun addTreeUri(uri: String) = ds.edit {
        val cur = it[TREE_URIS]?.split('\n')?.filter { s -> s.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (uri !in cur) cur.add(uri)
        it[TREE_URIS] = cur.joinToString("\n")
    }
    suspend fun removeTreeUri(uri: String) = ds.edit {
        val cur = it[TREE_URIS]?.split('\n')?.filter { s -> s.isNotBlank() && s != uri } ?: emptyList()
        it[TREE_URIS] = cur.joinToString("\n")
    }
    /** 置顶：把某 uri 移到最前 */
    suspend fun pinTreeUri(uri: String) = ds.edit {
        val cur = it[TREE_URIS]?.split('\n')?.filter { s -> s.isNotBlank() }?.toMutableList() ?: mutableListOf()
        cur.remove(uri); cur.add(0, uri)
        it[TREE_URIS] = cur.joinToString("\n")
    }

    suspend fun setOverride(key: String, point: PointF?) = ds.edit {
        val cur = (it[OVERRIDE_KEYS] ?: emptySet()).toMutableSet()
        cur.removeAll { s -> s.startsWith("$key:") }
        if (point != null) cur += "$key:${point.x},${point.y}"
        it[OVERRIDE_KEYS] = cur
    }

    /** 批量写入 21 键坐标（校准保存用，一次磁盘写而不是 21 次） */
    suspend fun setOverrides(points: Map<String, PointF>) = ds.edit {
        val cur = (it[OVERRIDE_KEYS] ?: emptySet()).toMutableSet()
        points.forEach { (key, p) ->
            cur.removeAll { s -> s.startsWith("$key:") }
            cur += "$key:${p.x},${p.y}"
        }
        it[OVERRIDE_KEYS] = cur
    }

    suspend fun clearOverrides() = ds.edit { it[OVERRIDE_KEYS] = emptySet() }
}
