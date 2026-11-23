package com.liquidlyre.app.data

/**
 * Better GI「原琴谱 / yuanqin」记谱格式解析器。
 *
 * 谱面形如（来自《开往春天的地铁》）：
 * ```
 * (NQ)[16]H[16]D[16]Q[16](NH)[16]D[16]...|
 * (BE)[16]J[16]G[16]E[16](BJ)[16]...
 * ```
 *
 * 规则：
 *  - `\n` 换行 = 换小节；行尾 `|` 是小节结束符（兼容无 `|` 的写法）
 *  - `(AB)` = 和弦，同时按下 A、B；单个字母 = 单键
 *  - `[n]` = n 分音符时值。`[16]` = 十六分音符 = 4/4 拍下的 1/4 拍。
 *    换算：时值ms = (4/n) × (60000/bpm)
 *  - `@` = 休止符，占时间但不发声
 *  - 末尾被截断的 `[1]` 等非标准时值按 `[16]` 容错
 *
 * 这份谱曾被误当成 BGI 文本谱：`[16]` 被整个吞掉，每小节 16 个音被均分到 1 拍，
 * 于是 2分29秒的曲子 37 秒播完（4 倍速）。本解析器按真实时值展开事件流，
 * 输出与 MIDI / BGI 解析器同构的 [ParsedScore]，播放引擎无需改动。
 *
 * 纯 Kotlin（不依赖 Android），可直接 JVM 单测。
 */
object YuanqinScoreParser {

    /** `(AB)[16]` / `C[8]` / `@[16]` */
    private val TOKEN = Regex("(?:\\(([A-Za-z]+)\\)|([A-Za-z@]))\\[(\\d+)\\]")
    private val VALID_DENOMS = setOf(2, 4, 8, 16, 32, 64)

    /** MIDI 嗅探之后的第二道嗅探；不能误吃 BGI 文本谱 */
    fun looksLikeYuanqin(type: String, notes: String): Boolean {
        if (type.contains("yuanqin", true) || type.contains("原琴")) return true
        if (!notes.contains('[') || !notes.contains(']')) return false
        return Regex("[A-Za-z@]\\s*\\[\\d+\\]").containsMatchIn(notes) ||
            Regex("\\)\\s*\\[\\d+\\]").containsMatchIn(notes)
    }

    fun parse(name: String, bpm: Int, timeSignature: String, notes: String): ParsedScore? {
        val safeBpm = bpm.coerceIn(20, 300)
        val beatMs = 60_000.0 / safeBpm
        val events = ArrayList<TimelineEvent>()
        val lastDown = HashMap<String, Long>()
        var cursor = 0.0
        var noteCount = 0

        val lines = notes.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in lines) {
            val body = line.trim().trimEnd('|').trim()
            if (body.isEmpty()) continue
            for (m in TOKEN.findAll(body)) {
                val rawDenom = m.groupValues[3].toIntOrNull() ?: 16
                // 容错：文件末尾可能被截断成 `[1]`，按十六分处理
                val denom = if (rawDenom in VALID_DENOMS) rawDenom else 16
                val durMs = 4.0 / denom * beatMs

                val chord = m.groupValues[1]
                val single = m.groupValues[2]
                val keys = when {
                    chord.isNotEmpty() -> chord.filter { it.uppercaseChar() in KeyMap.KEY_CHARS }
                        .map { it.uppercase() }
                    single == "@" -> emptyList<String>()     // 休止
                    else -> if (single.isNotEmpty() && single[0].uppercaseChar() in KeyMap.KEY_CHARS) listOf(single.uppercase()) else emptyList()
                }
                if (keys.isNotEmpty()) {
                    var downAt = cursor
                    keys.forEach { k ->
                        val prev = lastDown[k]
                        if (prev != null && downAt - prev < ScoreParser.MIN_GAP_TIME) {
                            downAt = (prev + ScoreParser.MIN_GAP_TIME).toDouble()
                        }
                    }
                    val down = downAt.toLong()
                    val hold = (durMs * 0.45).toLong().coerceIn(20L, 400L)
                    events += TimelineEvent(down, TimelineEvent.Action.DOWN, keys)
                    events += TimelineEvent(down + hold, TimelineEvent.Action.UP, keys)
                    keys.forEach { lastDown[it] = down }
                    noteCount++
                }
                cursor += durMs
            }
        }

        if (noteCount == 0) return null
        val sorted = events.sortedBy { it.timeMs }
        val end = (sorted.maxOfOrNull { it.timeMs } ?: 0L) + 1L
        return ParsedScore(
            name = name,
            bpm = safeBpm,
            timeSignature = timeSignature.ifBlank { "4/4" },
            events = sorted,
            durationMs = end,
            source = "yuanqin",
            noteCount = noteCount
        )
    }
}
