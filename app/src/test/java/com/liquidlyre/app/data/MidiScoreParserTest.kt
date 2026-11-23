package com.liquidlyre.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 谐律键琴 MIDI 事件流解析回归。
 *
 * 核心回归点：**别再把它当成 BGI 文本谱**。
 * 之前没有 `/` 与空格，整首 199 秒的曲子被压成「一拍」，1326 个事件在 0.6 秒内跑完，
 * 表现就是「键疯狂乱按 + 一点声音都没有」。
 */
class MidiScoreParserTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    // ---------- 嗅探 ----------

    @Test
    fun `嗅探 - 真实谐律键琴 json 识别为 midi`() {
        assertTrue(
            MidiScoreParser.looksLikeMidi("midi", "*96|DVQET0|UVQET168|DA72|UA168")
        )
    }

    @Test
    fun `嗅探 - 不看 type 也能从 notes 形状认出来`() {
        assertTrue(MidiScoreParser.looksLikeMidi("keyboard", "DQ0|UQ168|DA72|UA168"))
        // type 写了 midi 但 notes 是 BGI 文本 → 仍按 midi 试，解析会返回 null 而不是崩溃
        assertNull(MidiScoreParser.parse("x", 96, 480, "Q W E / Q W E"))
    }

    @Test
    fun `嗅探 - BGI 文本谱不会误判`() {
        assertFalse(MidiScoreParser.looksLikeMidi("keyboard", "Q W E R / Q W E R\nA S D F /"))
        assertFalse(MidiScoreParser.looksLikeMidi("keyboard", "(QAZ) W E /"))
    }

    // ---------- 时间换算 ----------

    @Test
    fun `tick 换算 - 96bpm 480ppq 下 168 tick 等于 218 毫秒`() {
        // 1 tick = 60000/(96*480) = 1.30208ms → 168 tick = 218.75ms
        val s = MidiScoreParser.parse("t", 96, 480, "DQ0|UQ168")!!
        val d = s.events.first { it.action == TimelineEvent.Action.DOWN }
        val u = s.events.first { it.action == TimelineEvent.Action.UP }
        assertEquals(0L, d.timeMs)
        assertEquals(219L, u.timeMs)
        assertEquals(220L, s.durationMs)
    }

    @Test
    fun `delta 是累加的 - 不是绝对值`() {
        val s = MidiScoreParser.parse("t", 96, 480, "DQ0|UQ168|DA72|UA168")!!
        val downs = s.events.filter { it.action == TimelineEvent.Action.DOWN }
        // 第二个 DOWN 在 168+72=240 tick = 312.5ms
        assertEquals(listOf(0L, 313L), downs.map { it.timeMs })
    }

    // ---------- 结构 ----------

    @Test
    fun `和弦 - 一个 token 里的多个键同时按下`() {
        val s = MidiScoreParser.parse("t", 96, 480, "DVQET0|UVQET168")!!
        val d = s.events.first()
        assertEquals(TimelineEvent.Action.DOWN, d.action)
        assertEquals(listOf("V", "Q", "E", "T"), d.keys)
    }

    @Test
    fun `元信息 - 星号 token 被跳过不占时间`() {
        val a = MidiScoreParser.parse("t", 96, 480, "DQ0|UQ168")!!
        val b = MidiScoreParser.parse("t", 96, 480, "*96|DQ0|UQ168")!!
        assertEquals(a.durationMs, b.durationMs)
        assertEquals(a.events.size, b.events.size)
    }

    @Test
    fun `容错 - 垃圾 token 跳过但有效事件保留`() {
        val s = MidiScoreParser.parse("t", 96, 480, "DQ0|#@!|UQ168|??")!!
        assertEquals(2, s.events.size)
    }

    @Test
    fun `过滤 - 21 键以外的字母不进时间轴`() {
        // P/K 不在风物之诗琴 21 键内
        val s = MidiScoreParser.parse("t", 96, 480, "DPK0|UPK168|DQ72|UQ168")!!
        val d = s.events.first { it.action == TimelineEvent.Action.DOWN }
        // DPK0 全被过滤 → Q 之前没有有效 DOWN；时间轴第一拍来自 DQ72
        assertTrue(d.keys.all { it in KeyMap.ALL_KEYS })
    }

    @Test
    fun `空输入返回 null 而不是空曲`() {
        assertNull(MidiScoreParser.parse("t", 96, 480, ""))
        assertNull(MidiScoreParser.parse("t", 96, 480, "*96"))
        assertNull(MidiScoreParser.parse("t", 96, 480, "UQ168|UQ168"))
    }

    // ---------- 真实文件回归 ----------

    @Test
    fun `真实谱 - 爸爸去哪儿 3 分 19 秒`() {
        val content = resource("midi_baba.json")
        val r = ScoreFileParser.parse("爸爸去哪儿.json", content)
        assertNull(r.error)
        val s = r.score!!

        assertEquals("midi", s.source)
        assertEquals(96, s.bpm)
        // 199.4 秒，与谱面描述「3分19秒」一致（±1s）
        val sec = s.durationMs / 1000.0
        assertTrue("实际时长 ${sec}s", sec in 198.0..201.0)

        val downs = s.events.filter { it.action == TimelineEvent.Action.DOWN }
        // 663 个按下事件（和弦合并后仍是 663 个时间点）
        assertTrue("down count=${downs.size}", downs.size in 600..750)

        // 起手：0ms 的 VQET 四音和弦，312ms 后是 A
        assertEquals(0L, downs[0].timeMs)
        assertEquals(listOf("V", "Q", "E", "T"), downs[0].keys)
        assertTrue(downs[1].timeMs in 300L..320L)

        // 所有键必须都在 21 键内，且不含动作字母残渣
        val bad = downs.flatMap { it.keys }.filter { it !in KeyMap.ALL_KEYS }
        assertTrue("非法键：$bad", bad.isEmpty())

        // 事件按时间单调不减
        val times = s.events.map { it.timeMs }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `真实谱 - 预览接口给对格式和时长`() {
        val p = ScoreFileParser.preview("爸爸去哪儿.json", resource("midi_baba.json"))!!
        assertEquals("midi", p.format)
        assertTrue(p.durationMs / 1000 in 198L..201L)
        assertTrue(p.notes > 600)
    }
}
