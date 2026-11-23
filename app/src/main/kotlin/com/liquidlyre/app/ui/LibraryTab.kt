package com.liquidlyre.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.liquidlyre.app.data.LibrarySort
import com.liquidlyre.app.data.ScoreIndexEntity
import com.liquidlyre.app.engine.PlayState
import com.liquidlyre.app.glass.LiquidButton
import com.liquidlyre.app.vm.MainViewModel

@Composable
fun LibraryTab(
    vm: MainViewModel,
    backdrop: Backdrop,
    glass: GlassParams,
    onAddDir: () -> Unit
) {
    val ui by vm.ui.collectAsState()
    val player by vm.player.collectAsState()
    val all by vm.repo.allEntries.collectAsState(initial = emptyList())
    val filtered = rememberFiltered(all, ui.query)
    var showSort by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16f.dp, vertical = 14f.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("曲库", style = glassTitleStyle)
                Box(Modifier.weight(1f))
                LiquidButton(onClick = { showSort = true }, backdrop = backdrop) {
                    BasicText("↕ ${sortLabel(ui.sortMode)}", style = glassSmallStyle)
                }
                LiquidButton(onClick = onAddDir, backdrop = backdrop) {
                    BasicText("＋ 目录", style = glassBodyStyle)
                }
            }
            GlassSearchBar(
                value = ui.query,
                onValueChange = { vm.setQuery(it) },
                hint = "搜索曲目…",
                backdrop = backdrop,
                glass = glass,
                modifier = Modifier.padding(vertical = 10f.dp)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("${filtered.size} 首 · 目录 ${ui.treeDirs.size} 个", style = glassSmallStyle)
                Box(Modifier.weight(1f))
                LiquidButton(onClick = { vm.scanLibrary() }, backdrop = backdrop) {
                    BasicText(if (ui.scanning) "扫描中…" else "↻ 刷新曲库", style = glassSmallStyle)
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    BasicText(
                        if (ui.treeDirs.isEmpty()) "还没有曲谱目录\n点右上「＋ 目录」添加存放 JSON 谱的文件夹\n（MIDI 事件流、原琴谱、BGI 文本谱都支持）"
                        else "没有匹配的曲目\n点「刷新曲库」重新扫描",
                        style = TextStyle(Color.White.copy(0.8f), 14f.sp, lineHeight = 22f.sp)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(top = 8f.dp),
                    verticalArrangement = Arrangement.spacedBy(8f.dp)
                ) {
                    items(filtered, key = { it.documentId }) { entry ->
                        ScoreRow(
                            entry = entry,
                            selected = player.current?.documentId == entry.documentId,
                            playing = player.state == PlayState.PLAYING && player.current?.documentId == entry.documentId,
                            backdrop = backdrop,
                            glass = glass,
                            onClick = { vm.selectScore(entry) },
                            onStar = { vm.star(entry) }
                        )
                    }
                }
            }
        }

        if (showSort) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                GlassListCard(backdrop, glass, title = "曲库排序") {
                    Column(verticalArrangement = Arrangement.spacedBy(6f.dp)) {
                        SORT_OPTIONS.forEach { (key, label) ->
                            LiquidButton(
                                onClick = { vm.setSortMode(key); showSort = false },
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BasicText(
                                    if (ui.sortMode == key) "✓ $label" else "  $label",
                                    style = glassBodyStyle
                                )
                            }
                        }
                        BasicText(
                            "时长由扫描时解析谱面得出；旧索引请先点「刷新曲库」",
                            style = glassSmallStyle
                        )
                        LiquidButton(
                            onClick = { showSort = false },
                            backdrop = backdrop,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            BasicText("关闭", style = glassSmallStyle)
                        }
                    }
                }
            }
        }
    }
}

private val SORT_OPTIONS = listOf(
    LibrarySort.RECENT to "新旧顺序（最新在前）",
    LibrarySort.NAME to "字母顺序（A→Z）",
    LibrarySort.DURATION to "时长顺序（短→长）"
)

private fun sortLabel(mode: String) = when (mode) {
    LibrarySort.RECENT -> "最新"
    LibrarySort.DURATION -> "时长"
    else -> "字母"
}

@Composable
private fun rememberFiltered(all: List<ScoreIndexEntity>, q: String): List<ScoreIndexEntity> {
    val query = q.trim()
    return if (query.isBlank()) all else all.filter { it.name.contains(query, true) }
}

@Composable
private fun ScoreRow(
    entry: ScoreIndexEntity,
    selected: Boolean,
    playing: Boolean,
    backdrop: Backdrop,
    glass: GlassParams,
    onClick: () -> Unit,
    onStar: () -> Unit
) {
    GlassListCard(backdrop, glass) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LiquidButton(onClick = onClick, backdrop = backdrop) {
                BasicText(
                    "${if (entry.starred) "★" else "♪"} ${entry.name}",
                    style = TextStyle(
                        if (selected) Color(0xFFFFC1D5) else Color.White,
                        14f.sp
                    )
                )
            }
            Box(Modifier.weight(1f))
            if (entry.durationMs > 0L) BasicText(MainViewModel.mmss(entry.durationMs), style = glassSmallStyle)
            if (entry.useCache) BasicText("高速", style = glassSmallStyle)
            if (playing) BasicText("▶", style = TextStyle(Color(0xFF7CFF9B), 14f.sp))
            LiquidButton(onClick = onStar, backdrop = backdrop) {
                BasicText(if (entry.starred) "★" else "☆", style = glassBodyStyle)
            }
        }
    }
}
