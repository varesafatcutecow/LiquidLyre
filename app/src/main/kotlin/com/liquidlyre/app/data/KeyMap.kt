package com.liquidlyre.app.data

import android.graphics.PointF

/**
 * 原神 21 键琴键坐标表（iQOO 15 横屏 3168×1440 原生像素）。
 *
 * 不用 21 个独立数值硬编码，按 锚点 + 列间距 + 行间距 推算，换分辨率/UI 缩放后可整体标定。
 * 锚点 (748,758)；列间距 278.9px；行间距 222.5px。
 * 校准后用户覆盖值保存到 DataStore，pointOf 优先用覆盖值。
 */
object KeyMap {
    const val ANCHOR_X = 748f
    const val ANCHOR_Y = 758f
    const val COL_GAP = 278.9f
    const val ROW_GAP = 222.5f
    const val KEY_RADIUS = 31f

    val HIGH = listOf("Q", "W", "E", "R", "T", "Y", "U")
    val MID = listOf("A", "S", "D", "F", "G", "H", "J")
    val LOW = listOf("Z", "X", "C", "V", "B", "N", "M")
    val ALL_ROWS = listOf(HIGH, MID, LOW)
    val ALL_KEYS = HIGH + MID + LOW
    /** 首字母集合，供逐字符过滤（MIDI 事件流按字符拆键名） */
    val KEY_CHARS: Set<Char> = ALL_KEYS.map { it[0] }.toSet()

    /** 行列索引（纯 Kotlin，便于 JVM 单测） */
    fun rowCol(key: String): Pair<Int, Int>? {
        val k = key.trim().uppercase()
        ALL_ROWS.forEachIndexed { row, keys ->
            val col = keys.indexOf(k)
            if (col >= 0) return row to col
        }
        return null
    }

    /** 默认 X（锚点 + 列间距推算，纯函数可单测） */
    fun defaultX(key: String): Float? = rowCol(key)?.let { (_, col) -> ANCHOR_X + COL_GAP * col }

    /** 默认 Y（锚点 + 行间距推算，纯函数可单测） */
    fun defaultY(key: String): Float? = rowCol(key)?.let { (row, _) -> ANCHOR_Y + ROW_GAP * row }

    /** 默认坐标：锚点 + 间距推算 */
    fun defaultPoint(key: String): PointF? {
        val rc = rowCol(key) ?: return null
        return PointF(ANCHOR_X + COL_GAP * rc.second, ANCHOR_Y + ROW_GAP * rc.first)
    }

    /** 带校准覆盖的取点 */
    fun pointOf(key: String, override: Map<String, PointF>? = null): PointF? {
        if (!override.isNullOrEmpty()) override[key.trim().uppercase()]?.let { return it }
        return defaultPoint(key)
    }
}
