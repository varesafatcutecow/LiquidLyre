package com.liquidlyre.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {
    private fun e(name: String, modified: Long, duration: Long, star: Boolean = false) =
        ScoreIndexEntity(
            documentId = name, name = name, uriString = name, treeUriString = "t",
            size = 0L, modifiedMs = modified, starred = star, durationMs = duration
        )

    private val list = listOf(
        e("MONSTER", 300L, 241_900L),
        e("爸爸去哪儿", 100L, 199_400L, star = true),
        e("开往春天的地铁", 200L, 145_600L),
        e("未生成时长", 400L, 0L)
    )

    @Test
    fun `字母顺序 - 星标置顶其余按名字`() {
        val got = ScoreRepository.sortEntries(list, LibrarySort.NAME).map { it.name }
        assertEquals(listOf("爸爸去哪儿", "MONSTER", "开往春天的地铁", "未生成时长"), got)
    }

    @Test
    fun `新旧顺序 - 星标置顶其余按修改时间降序`() {
        val got = ScoreRepository.sortEntries(list, LibrarySort.RECENT).map { it.name }
        assertEquals(listOf("爸爸去哪儿", "未生成时长", "MONSTER", "开往春天的地铁"), got)
    }

    @Test
    fun `时长顺序 - 短的在前且未知时长沉底`() {
        val got = ScoreRepository.sortEntries(list, LibrarySort.DURATION).map { it.name }
        assertEquals(listOf("爸爸去哪儿", "开往春天的地铁", "MONSTER", "未生成时长"), got)
    }

    @Test
    fun `空列表与未知模式不崩`() {
        assertEquals(emptyList<String>(), ScoreRepository.sortEntries(emptyList(), LibrarySort.NAME).map { it.name })
        assertEquals(4, ScoreRepository.sortEntries(list, "unknown").size)
    }
}
