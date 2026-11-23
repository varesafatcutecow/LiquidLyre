package com.liquidlyre.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读谱规则单测。核心回归点：**一拍内的基本单位均分这一拍**。
 * 旧实现把"每个空格"写死成 1/4 拍，一拍只有两个单位时会快整整一倍。
 */
class ScoreParserTest {

    private fun downs(notes: String, bpm: Int = 60, sig: String = "4/4"): List<TimelineEvent> =
        ScoreParser.parse(notes, bpm, sig).first.filter { it.action == TimelineEvent.Action.DOWN }

    // ---------- 均分 ----------

    @Test
    fun `一拍四个单位 - 各占四分之一拍`() {
        // bpm60 4/4 → 一拍 1000ms，4 个单位各 250ms
        val d = downs("Q W E R /")
        assertEquals(listOf(0L, 250L, 500L, 750L), d.map { it.timeMs })
    }

    @Test
    fun `一拍两个单位 - 各占半拍_旧实现会快一倍`() {
        // 这是"弹得特别快"的核心回归：一拍 2 个单位应各 500ms，而不是 250ms
        val d = downs("Q W /")
        assertEquals(listOf(0L, 500L), d.map { it.timeMs })
    }

    @Test
    fun `一拍八个单位 - 各占八分之一拍`() {
        val d = downs("Q W E R T Y U J /")
        assertEquals(listOf(0L, 125L, 250L, 375L, 500L, 625L, 750L, 875L), d.map { it.timeMs })
    }

    @Test
    fun `不同单位数的两拍速度各自均分`() {
        // 第一拍 2 个（各 500），第二拍 4 个（各 250）
        val d = downs("Q W / Q W E R /")
        assertEquals(listOf(0L, 500L, 1000L, 1250L, 1500L, 1750L), d.map { it.timeMs })
    }

    @Test
    fun `多行小节时间连续推进`() {
        val d = downs("Q W /\nE R /")
        assertEquals(listOf(0L, 500L, 1000L, 1500L), d.map { it.timeMs })
    }

    // ---------- 休止符 ----------

    @Test
    fun `连续空格是休止符 - 占一格不发声`() {
        // "A  B" 切成 [A, 空, B] 三格 → A@0, B@666
        val d = downs("A  B /")
        assertEquals(2, d.size)
        assertEquals(0L, d[0].timeMs)
        assertEquals(667L, d[1].timeMs)
    }

    @Test
    fun `空拍整拍休止`() {
        val d = downs("Q W / / E R /")
        // 第二拍全体止 → E/R 从 2000 起
        assertEquals(listOf(0L, 500L, 2000L, 2500L), d.map { it.timeMs })
    }

    // ---------- 和弦 / 连续按键 ----------

    @Test
    fun `括号和弦 - 一个事件同时按下多键`() {
        val d = downs("(QE) /")
        assertEquals(1, d.size)
        assertEquals(setOf("Q", "E"), d[0].keys.toSet())
        assertEquals(0L, d[0].timeMs)
    }

    @Test
    fun `连续字母 - 块均分单位时长`() {
        // "QWE" 是一个基本单位，3 块均分 1000ms → 0/333/666
        val d = downs("QWE /")
        assertEquals(listOf(0L, 333L, 667L), d.map { it.timeMs })
        assertEquals(listOf(listOf("Q"), listOf("W"), listOf("E")), d.map { it.keys })
    }

    @Test
    fun `和弦与连续按键混合 - 块均分且块内同时按`() {
        // "(AB)C(DE)" 三块均分 1000ms
        val d = downs("(AB)C(DE) /")
        assertEquals(3, d.size)
        assertEquals(0L, d[0].timeMs)
        assertEquals(setOf("A", "B"), d[0].keys.toSet())
        assertEquals(333L, d[1].timeMs)
        assertEquals(setOf("C"), d[1].keys.toSet())
        assertEquals(667L, d[2].timeMs)
        assertEquals(setOf("D", "E"), d[2].keys.toSet())
    }

    @Test
    fun `块切分 - 括号整体一块_单字母各一块`() {
        val blocks = ScoreParser.parseBlocks("(AB)C(DE)")
        assertEquals(listOf(listOf("A", "B"), listOf("C"), listOf("D", "E")), blocks)
        assertEquals(listOf(listOf("A"), listOf("B"), listOf("C")), ScoreParser.parseBlocks("ABC"))
        assertEquals(listOf(listOf("A", "B", "C")), ScoreParser.parseBlocks("(ABC)"))
    }

    // ---------- 拍号 ----------

    @Test
    fun `拍号倍数 - 4分之4为1_6分之8为1点5_4分之8为2`() {
        assertEquals(1.0f, ScoreParser.beatMultiplier("4/4"), 0.001f)
        assertEquals(1.0f, ScoreParser.beatMultiplier("3/4"), 0.001f)
        assertEquals(1.5f, ScoreParser.beatMultiplier("6/8"), 0.001f)
        assertEquals(1.5f, ScoreParser.beatMultiplier("9/8"), 0.001f)
        assertEquals(2.0f, ScoreParser.beatMultiplier("4/8"), 0.001f)
        assertEquals(0.5f, ScoreParser.beatMultiplier("2/2"), 0.001f)
        assertTrue(ScoreParser.isCompound("6/8"))
        assertFalse(ScoreParser.isCompound("4/4"))
        assertFalse(ScoreParser.isCompound("4/8"))
    }

    @Test
    fun `6分之8 - 一拍1500ms_两个单位各750`() {
        val d = downs("Q W /", 60, "6/8")
        assertEquals(listOf(0L, 750L), d.map { it.timeMs })
    }

    @Test
    fun `4分之8 - 一拍2000ms_两个单位各1000`() {
        val d = downs("Q W /", 60, "4/8")
        assertEquals(listOf(0L, 1000L), d.map { it.timeMs })
    }

    // ---------- 同键保护 / 脉冲 ----------

    @Test
    fun `同键极大密度 - DOWN间隔不小于MIN_GAP`() {
        // bpm300 → 一拍200ms，16 个单位各 12.5ms < 25ms，必须被顺延到 ≥25
        val text = (1..16).joinToString(" ") { "Q" } + " /"
        val d = downs(text, 300)
        assertEquals(16, d.size)
        for (i in 1 until d.size) {
            assertTrue(
                "第$i 个间隔 ${d[i].timeMs - d[i - 1].timeMs}ms 应 >= ${ScoreParser.MIN_GAP_TIME}",
                d[i].timeMs - d[i - 1].timeMs >= ScoreParser.MIN_GAP_TIME
            )
        }
    }

    @Test
    fun `脉冲发音 - UP紧跟在DOWN之后且不超过25ms`() {
        val (ev, _) = ScoreParser.parse("Q W E R /", 60, "4/4")
        val down = ev.first { it.action == TimelineEvent.Action.DOWN }
        val up = ev.first { it.action == TimelineEvent.Action.UP && it.timeMs >= down.timeMs }
        val hold = up.timeMs - down.timeMs
        assertTrue("hold=$hold 应在 10..25ms", hold in 10..25)
    }

    @Test
    fun `结束时刻等于最后事件加一`() {
        val (ev, end) = ScoreParser.parse("Q W /", 60, "4/4")
        assertEquals((ev.maxOf { it.timeMs }) + 1L, end)
    }

    // ---------- 坐标（保留） ----------

    @Test
    fun `坐标推算 - 锚点加间距还原21键`() {
        assertEquals(KeyMap.ANCHOR_X, KeyMap.defaultX("Q")!!, 0.01f)
        assertEquals(KeyMap.ANCHOR_Y, KeyMap.defaultY("Q")!!, 0.01f)
        assertEquals(KeyMap.ANCHOR_X + KeyMap.COL_GAP * 6, KeyMap.defaultX("U")!!, 0.01f)
        assertEquals(KeyMap.ANCHOR_Y + KeyMap.ROW_GAP, KeyMap.defaultY("A")!!, 0.01f)
        assertEquals(KeyMap.ANCHOR_Y + KeyMap.ROW_GAP * 2, KeyMap.defaultY("M")!!, 0.01f)
        assertEquals(21, KeyMap.ALL_KEYS.size)
    }
}
