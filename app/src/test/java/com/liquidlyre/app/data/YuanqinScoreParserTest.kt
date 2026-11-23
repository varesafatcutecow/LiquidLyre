package com.liquidlyre.app.data

import org.junit.Assert.*
import org.junit.Test

class YuanqinScoreParserTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test
    fun `嗅探 - 原琴谱不会被误认成 MIDI 或 BGI`() {
        val notes = resource("yuanqin_subway.json")
        assertTrue(YuanqinScoreParser.looksLikeYuanqin("yuanqin", notes))
        assertFalse(MidiScoreParser.looksLikeMidi("yuanqin", notes))
        assertFalse(YuanqinScoreParser.looksLikeYuanqin("midi", "DQ72|UQ168|DVQET0"))
    }

    @Test
    fun `三份真实谱各走各的分支`() {
        val subway = ScoreFileParser.parse("开往春天的地铁.json", resource("yuanqin_subway.json"))
        val baba = ScoreFileParser.parse("爸爸去哪儿.json", resource("midi_baba.json"))
        val monster = ScoreFileParser.parse("MONSTER.json", resource("midi_monster.json"))

        assertNull(subway.error); assertNull(baba.error); assertNull(monster.error)
        assertEquals("yuanqin", subway.score!!.source)
        assertEquals("midi", baba.score!!.source)
        assertEquals("midi", monster.score!!.source)
    }

    @Test
    fun `真实谱 - 时长不再是 37 秒而是约 145 秒`() {
        val r = ScoreFileParser.parse("开往春天的地铁.json", resource("yuanqin_subway.json"))
        assertNull(r.error)
        val s = r.score!!
        // 40 个完整小节 × 16 个十六分 + 末尾截断 1 个；bpm=66 → 十六分 = 227.27ms
        assertTrue("实际 ${s.durationMs}", s.durationMs in 145_000L..146_000L)
        assertEquals(640, s.noteCount)
        assertEquals(66, s.bpm)
    }

    @Test
    fun `真实谱 - 起手是 NQ 和弦且按真时值等距展开`() {
        val r = ScoreFileParser.parse("开往春天的地铁.json", resource("yuanqin_subway.json"))
        val downs = r.score!!.events.filter { it.action == TimelineEvent.Action.DOWN }
        assertEquals(listOf("N", "Q"), downs.first().keys)
        assertEquals(0L, downs.first().timeMs)

        // 前 16 个音都是 [16]，间隔应等于 4/16 拍 = 227.27ms
        val gaps = (1 until 16).map { downs[it].timeMs - downs[it - 1].timeMs }
        assertTrue("间隔 $gaps", gaps.all { it in 226L..229L })
    }

    @Test
    fun `时值换算 - n分音符等于 4除n 拍`() {
        // bpm=120 → 一拍 500ms；[8]=250ms [4]=500ms [16]=125ms [32]=62.5ms
        val s = YuanqinScoreParser.parse("t", 120, "4/4", "A[8]B[4]C[16]D[32]")!!
        val downs = s.events.filter { it.action == TimelineEvent.Action.DOWN }
        assertEquals(listOf(0L, 250L, 750L, 875L), downs.map { it.timeMs })
        assertEquals(4, s.noteCount)
    }

    @Test
    fun `休止符 - at 占时间但不发声`() {
        // bpm=120 → [16]=125ms；A在0ms，@在125ms休止，B在250ms
        val s = YuanqinScoreParser.parse("t", 120, "4/4", "A[16]@[16]B[16]")!!
        val downs = s.events.filter { it.action == TimelineEvent.Action.DOWN }
        assertEquals(2, s.noteCount)
        assertEquals(listOf(0L, 250L), downs.map { it.timeMs })
    }

    @Test
    fun `截断容错 - 末尾非标时值按十六分处理`() {
        val s = YuanqinScoreParser.parse("t", 120, "4/4", "(ND)[1]")
        assertNotNull(s)
        assertEquals(1, s!!.noteCount)
        assertEquals(listOf("N", "D"), s.events.first { it.action == TimelineEvent.Action.DOWN }.keys)
    }

    @Test
    fun `逐小节累加无漂移`() {
        // 4 小节 × 16 个 [16]，bpm=60 → 每音 250ms，第 64 个 DOWN = 63×250 = 15750ms
        val bars = (0 until 4).joinToString("\n") { bar ->
            (0 until 16).joinToString("") { "Q[16]" } + "|"
        }
        val s = YuanqinScoreParser.parse("t", 60, "4/4", bars)!!
        val downs = s.events.filter { it.action == TimelineEvent.Action.DOWN }
        assertEquals(64, downs.size)
        assertEquals(15_750L, downs.last().timeMs)
    }
}
