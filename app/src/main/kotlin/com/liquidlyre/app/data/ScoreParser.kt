package com.liquidlyre.app.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * BGI「原琴助手 / CuSimpAutoGenshinLyre」键盘谱 → 扁平时间轴编译器。
 *
 * 严格按官方读谱规则（v0.1.10）实现。之前"空格固定 = 1/4 拍"是错的，会导致整体变速：
 *
 *  1. 一拍时长 gap = 60000/bpm，再乘拍号分母因子：
 *     - 4/4、3/4、2/4 → 1.0
 *     - 6/8、9/8、12/8（分子是 3 的倍数）→ 1.5（附点四分 = 复合拍物理单位）
 *     - 其他分母 → 分母/4
 *  2. `\n` 分行（小节）；行内 `/` 分拍（`/` = 一拍停止符）。
 *  3. **一拍内的所有"基本单位"均分这一拍** —— 速度正确与否的关键。
 *  4. 一拍内按空格切分单位；**连续空格 = 休止符**（占一格但不发声）。
 *  5. 单位内部用正则 `(\([A-Za-z]+\))|([A-Za-z])` 切块：
 *     - `ABC`  → 3 块（连续按键）
 *     - `(AB)` → 1 块（同时按下）
 *     - `(AB)C(DE)` → 3 块，块内同时按
 *     多块时**块均分单位时长**。
 *  6. 脉冲式发音：按下 hold = min(25ms, 块时长/2)，其余时间抬起留空窗防吞键。
 *  7. 同一键两次 DOWN 间隔 ≥ MIN_GAP_TIME(25ms)，不足则顺延。
 *
 * 纯 Kotlin（不含 Android 类），可直接 JVM 单元测试。
 */
object ScoreParser {

    const val MIN_GAP_TIME = 25L

    /** 拍号 → 相对于四分音符的倍数 */
    fun beatMultiplier(timeSignature: String): Float {
        val s = timeSignature.trim()
        val num = s.substringBefore('/', "").toIntOrNull() ?: 4
        val den = s.substringAfter('/', "").toIntOrNull() ?: 4
        return when {
            den == 8 && num % 3 == 0 -> 1.5f   // 6/8 9/8 12/8 复合拍 = 附点四分
            else -> den / 4f                    // 4/4→1.0  3/4→1.0  4/8→2.0  2/2→0.5
        }
    }

    /** 是否为复合拍（6/8、9/8、12/8） */
    fun isCompound(timeSignature: String): Boolean {
        val s = timeSignature.trim()
        val num = s.substringBefore('/', "").toIntOrNull() ?: 4
        val den = s.substringAfter('/', "").toIntOrNull() ?: 4
        return den == 8 && num % 3 == 0
    }

    /**
     * 单位块切分：`(AB)C(DE)` → [[A,B],[C],[D,E]]
     * 同时兼容方括号和弦 `[Q A Z]`（社区浏览器琴谱常见写法）。
     */
    private val BLOCK_REGEX = Regex("[\\(\\[]([A-Za-z\\s]+)[\\)\\]]|([A-Za-z])")

    fun parseBlocks(unit: String): List<List<String>> {
        val blocks = ArrayList<List<String>>()
        for (m in BLOCK_REGEX.findAll(unit)) {
            val chord = m.groupValues[1]
            if (chord.isNotEmpty()) {
                val keys = chord.filter { it.isLetter() }.map { it.uppercase() }
                if (keys.isNotEmpty()) blocks.add(keys)
            } else {
                val one = m.groupValues[2]
                if (one.isNotEmpty()) blocks.add(listOf(one.uppercase()))
            }
        }
        return blocks
    }

    /**
     * @return Pair(事件时间轴, 结束时刻ms)
     */
    fun parse(notes: String, bpm: Int, timeSignature: String): Pair<List<TimelineEvent>, Long> {
        val safeBpm = bpm.coerceIn(20, 300)
        val beat = 60_000.0 / safeBpm * beatMultiplier(timeSignature)

        val events = ArrayList<TimelineEvent>()
        val lastDown = HashMap<String, Long>()

        /** 单块发音：同键保护 + 脉冲 hold */
        fun emit(keys: List<String>, desiredDownMs: Double, blockMs: Double) {
            var d = desiredDownMs
            keys.forEach { k ->
                val prev = lastDown[k]
                if (prev != null && d - prev < MIN_GAP_TIME) d = (prev + MIN_GAP_TIME).toDouble()
            }
            val down = d.roundToLong()
            // 脉冲：占块时长一半、不超过 25ms；最短 10ms
            val hold = min(MIN_GAP_TIME, max(10L, (blockMs * 0.5).roundToLong()))
            val up = down + hold
            events += TimelineEvent(down, TimelineEvent.Action.DOWN, keys)
            events += TimelineEvent(up, TimelineEvent.Action.UP, keys)
            keys.forEach { lastDown[it] = down }
        }

        var cursor = 0.0
        val lines = notes.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in lines) {
            val stripped = line.trim()
            if (stripped.isEmpty()) continue
            val rawBeats = stripped.split('/')
            for (bi in rawBeats.indices) {
                val beatStr = rawBeats[bi].trim()
                val isLast = bi == rawBeats.size - 1
                // 以 / 结尾时最后一段为空 → 那是上一拍的收尾，不额外占时间
                if (isLast && beatStr.isEmpty()) continue
                if (beatStr.isEmpty()) {
                    cursor += beat        // 空拍 = 整拍休止
                    continue
                }
                // 一拍内的基本单位：空格切分，保留中间空串 = 休止符
                val units = beatStr.split(' ')
                val unitMs = beat / units.size
                for ((ui, u) in units.withIndex()) {
                    if (u.isEmpty()) continue          // 休止符占格不发声
                    val blocks = parseBlocks(u)
                    if (blocks.isEmpty()) continue
                    val unitStart = cursor + ui * unitMs
                    val blockMs = unitMs / blocks.size
                    for ((bIdx, keys) in blocks.withIndex()) {
                        emit(keys, unitStart + bIdx * blockMs, blockMs)
                    }
                }
                cursor += beat
            }
        }

        val sorted = events.sortedBy { it.timeMs }
        val end = (sorted.maxOfOrNull { it.timeMs } ?: 0L) + 1L
        return sorted to end
    }

    /** 便捷：从 ParsedScore 需要的字段一次构造 */
    fun parseScore(name: String, bpm: Int, timeSignature: String, notes: String, durationMs: Long = 0L): ParsedScore {
        val (ev, computedEnd) = parse(notes, bpm, timeSignature)
        return ParsedScore(
            name = name,
            bpm = bpm,
            timeSignature = timeSignature,
            events = ev,
            durationMs = if (durationMs > 0) durationMs else computedEnd,
            source = "bgi",
            noteCount = ev.count { it.action == TimelineEvent.Action.DOWN }
        )
    }
}
