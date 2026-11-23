package com.liquidlyre.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从谱子文件内容解析为 ParsedScore。三种格式：
 *  1) 谐律键琴 MIDI 事件流：{"type":"midi","bpm":96,"ticks":480,"notes":"*96|DVQET0|UVQET168|…"}
 *     —— B 站键盘谱最常见的一种，纯事件流，不带小节/空格
 *  2) BGI 语义键盘谱：{"name":"珊瑚海","bpm":"80","type":"keyboard","notes":"Q W E / …"}
 *  3) BGI cache 高速模式：[[{"time":0,"action":"down","keys":["Q"]}, …]]
 *
 * 解析失败会带**可读原因**（而不是静默出空曲），这样用户能一眼看出 JSON 能不能直接用。
 */
object ScoreFileParser {

    data class Result(val score: ParsedScore?, val error: String?)
    data class Preview(val name: String, val format: String, val durationMs: Long, val notes: Int)

    fun parse(fileName: String, content: String): Result = try {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result(null, "空文件")
        if (trimmed.startsWith("[")) {
            val cache = parseCache(fileName, JSONArray(trimmed))
            if (cache.events.isEmpty()) Result(null, "cache 时间轴里没有事件")
            else Result(cache, null)
        } else {
            val obj = JSONObject(trimmed)
            val name = obj.optString("name").ifBlank { fileName.removeSuffix(".json") }

            // bpm 兼容：数字 / "80" / "80.5" / 大写 BPM
            val bpmRaw = obj.opt("bpm") ?: obj.opt("BPM") ?: obj.opt("tempo")
            val bpm = when (bpmRaw) {
                is Number -> bpmRaw.toInt()
                else -> bpmRaw?.toString()?.trim()?.toDoubleOrNull()?.toInt()
            } ?: 80

            // 拍号兼容 time_signature / timeSignature
            val sig = obj.optString("time_signature").ifBlank {
                obj.optString("timeSignature").ifBlank { "4/4" }
            }
            val notes = obj.optString("notes")
            val type = obj.optString("type").ifBlank { "keyboard" }.lowercase()
            val ticks = obj.opt("ticks")?.toString()?.toIntOrNull()
                ?: obj.opt("ppq")?.toString()?.toIntOrNull() ?: 480

            when {
                notes.isBlank() ->
                    Result(null, "缺少 notes 字段（本工具需要键盘谱的 notes 字符串）")

                // ① 谐律键琴 MIDI 事件流（B 站键盘谱 / 原琴谱 json）
                MidiScoreParser.looksLikeMidi(type, notes) -> {
                    val score = MidiScoreParser.parse(name, bpm, ticks, notes)
                    if (score == null || score.events.isEmpty()) {
                        Result(null, "MIDI 谱读不出事件：notes 需形如 DQ72|UQ168|DVQET0（D/U + 键名 + tick 差）")
                    } else {
                        Result(score, null)
                    }
                }

                // ② Better GI 原琴谱：(AB)[16]C[16]...，`[n]` 是 n 分音符时值
                YuanqinScoreParser.looksLikeYuanqin(type, notes) -> {
                    val score = YuanqinScoreParser.parse(name, bpm, sig, notes)
                    if (score == null || score.events.isEmpty()) {
                        Result(null, "原琴谱读不出事件：notes 需形如 (NQ)[16]H[16]D[16]（括号=和弦，[n]=n分音符）")
                    } else {
                        Result(score, null)
                    }
                }

                // ③ BGI 文本键盘谱
                !notes.any { it.isLetter() } ->
                    Result(null, "notes 里没有字母音符，当前 type=$type。支持谐律键琴 MIDI、原琴谱与 QWERTYU/ASDFGHJ/ZXCVBNM 字母谱")

                else -> {
                    val score = ScoreParser.parseScore(name, bpm, sig, notes)
                    if (score.events.isEmpty()) {
                        Result(null, "读不出音符：请确认 notes 是字母键盘谱，且没有出现无法识别的符号")
                    } else {
                        Result(score, null)
                    }
                }
            }
        }
    } catch (t: Throwable) {
        Result(null, "JSON 损坏：${t.message ?: t.javaClass.simpleName}")
    }

    /** 只取元信息，用于曲库列表展示时长/格式，不建完整时间轴（省内存） */
    fun preview(fileName: String, content: String): Preview? = try {
        val obj = JSONObject(content.trim())
        val name = obj.optString("name").ifBlank { fileName.removeSuffix(".json") }
        val type = obj.optString("type").lowercase()
        val notes = obj.optString("notes")
        val bpm = obj.opt("bpm")?.toString()?.toDoubleOrNull()?.toInt() ?: 80
        val ticks = obj.opt("ticks")?.toString()?.toIntOrNull() ?: 480
        if (MidiScoreParser.looksLikeMidi(type, notes)) {
            val s = MidiScoreParser.parse(name, bpm, ticks, notes)
            Preview(name, "midi", s?.durationMs ?: 0L, s?.noteCount ?: 0)
        } else if (YuanqinScoreParser.looksLikeYuanqin(type, notes)) {
            val s = YuanqinScoreParser.parse(name, bpm, "4/4", notes)
            Preview(name, "yuanqin", s?.durationMs ?: 0L, s?.noteCount ?: 0)
        } else if (notes.isNotBlank()) {
            val s = ScoreParser.parseScore(name, bpm, "4/4", notes)
            Preview(name, "bgi", s.durationMs, s.noteCount)
        } else null
    } catch (_: Throwable) { null }

    /** BGI cache：[[{time,action:"down"|"up",keys:[...]}]] */
    private fun parseCache(fileName: String, outer: JSONArray): ParsedScore {
        val events = ArrayList<TimelineEvent>()
        for (i in 0 until outer.length()) {
            val group = outer.optJSONArray(i) ?: continue
            for (j in 0 until group.length()) {
                val o = group.optJSONObject(j) ?: continue
                val time = o.optLong("time")
                val action = when (o.optString("action").lowercase()) {
                    "down" -> TimelineEvent.Action.DOWN
                    "up" -> TimelineEvent.Action.UP
                    else -> continue
                }
                val keysArr = o.optJSONArray("keys") ?: continue
                val keys = (0 until keysArr.length()).mapNotNull { keysArr.optString(it).takeIf { s -> s.isNotBlank() } }
                if (keys.isNotEmpty()) events += TimelineEvent(time, action, keys)
            }
        }
        val sorted = events.sortedBy { it.timeMs }
        val end = (sorted.maxOfOrNull { it.timeMs } ?: 0L) + 1L
        return ParsedScore(
            fileName.removeSuffix(".json"), 80, "cache", sorted, end,
            source = "cache", noteCount = sorted.count { it.action == TimelineEvent.Action.DOWN }
        )
    }
}
