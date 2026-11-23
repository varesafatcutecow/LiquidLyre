package com.liquidlyre.app.data

/**
 * 「谐律键琴」MIDI 事件流谱解析器（B 站键盘谱 JSON 常见格式）。
 *
 * 形如：
 * ```json
 * {"name":"爸爸去哪儿","type":"midi","bpm":96,"ticks":480,
 *  "notes":"*96|DVQET0|UVQET168|DA72|UA168|DGQ72|…"}
 * ```
 * - `|` 分隔事件；`*` 开头的 token（如 `*96`）是元信息，跳过
 * - 每个事件 `[D|U] + 键名们 + deltaTicks`
 *   - `D` = 按下（Down），`U` = 抬起（Up）
 *   - `DVQET0` = 同时按下 V/Q/E/T，与上一事件的 tick 差为 0
 *   - `UVQET168` = 抬起 V/Q/E/T，与上一事件差 168 tick
 * - delta **累加**得绝对 tick，再换算：1 tick = 60000 / (bpm × ticks) ms
 *
 * 这是纯事件流，不需要拍号/小节/空格语义 —— 之前把它塞进 BGI 文本解析器，
 * 没有 `/` 和空格，整首曲子被压成「一拍」，于是键疯狂乱按、声音糊成一团。
 *
 * 纯 Kotlin（不依赖 Android），可直接 JVM 单元测试。
 */
object MidiScoreParser {

    /** 单事件 token：D/U + 1~8 个大写键名 + delta tick */
    private val TOKEN = Regex("^([DU])([A-Z]{1,8})(\\d+)$")

    /**
     * 嗅探：这个 JSON 是不是 MIDI 事件流。
     * 有些谱 type 写成 "midi"/"harp"/"keyboard"，不能只信 type，直接看 notes 的形状。
     */
    fun looksLikeMidi(type: String, notes: String): Boolean {
        if (type.contains("midi", true) || type.contains("harp", true)) return true
        val firstReal = notes.split('|', '\n')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("*") }
            ?: return false
        return TOKEN.matches(firstReal.uppercase())
    }

    /**
     * @return null 表示一个有效事件都没有（不是这种格式 / 全是垃圾）
     */
    fun parse(name: String, bpm: Int, ticks: Int, notes: String): ParsedScore? {
        val safeBpm = bpm.coerceIn(20, 300)
        val ppq = if (ticks > 0) ticks else 480
        val msPerTick = 60_000.0 / (safeBpm * ppq)

        // 同一时刻、同一动作的键合并成一个事件，事件数从 1300+ 降到 ~660
        val buckets = LinkedHashMap<Long, Pair<TimelineEvent.Action, MutableList<String>>>()
        var tick = 0L
        var totalTokens = 0
        var usedTokens = 0

        val raw = notes.split('|', '\n', '\r', ' ', '\t', ',')
        for (tokenRaw in raw) {
            val token = tokenRaw.trim().uppercase()
            if (token.isEmpty()) continue
            if (token.startsWith("*")) continue          // 元信息：*96 = bpm 标记
            totalTokens++
            val m = TOKEN.matchEntire(token) ?: continue
            val action = if (m.groupValues[1] == "D") TimelineEvent.Action.DOWN else TimelineEvent.Action.UP
            val keys = m.groupValues[2].filter { it in KeyMap.KEY_CHARS }.map { it.toString() }
            val delta = m.groupValues[3].toLongOrNull() ?: continue
            if (delta < 0) continue
            tick += delta
            usedTokens++
            if (keys.isEmpty()) continue
            val timeMs = Math.round(tick * msPerTick)
            val key = timeMs
            val existing = buckets[key]
            if (existing != null && existing.first == action) {
                existing.second.addAll(keys)
            } else if (existing == null) {
                buckets[key] = action to keys.toMutableList()
            } else {
                // 同 ms 内先 D 后 U（极短装饰音）：用 +1ms 错开，避免丢事件
                val alt = timeMs + 1
                val e2 = buckets[alt]
                if (e2 != null && e2.first == action) e2.second.addAll(keys)
                else buckets[alt] = action to keys.toMutableList()
            }
        }

        if (usedTokens == 0) return null
        if (buckets.isEmpty()) return null

        val events = buckets.entries
            .sortedBy { it.key }
            .map { (t, v) -> TimelineEvent(t, v.first, v.second.distinct()) }

        val downs = events.count { it.action == TimelineEvent.Action.DOWN }
        if (downs == 0) return null

        val end = (events.maxOfOrNull { it.timeMs } ?: 0L) + 1L
        return ParsedScore(
            name = name,
            bpm = safeBpm,
            timeSignature = "MIDI ${ppq}ppq",
            events = events,
            durationMs = end,
            // 标记来源，UI 用来显示「谐律键琴」
            source = "midi",
            noteCount = downs
        )
    }
}
