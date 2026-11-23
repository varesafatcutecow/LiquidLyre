package com.liquidlyre.app.data

/** 时间轴事件：某时刻对一组键按下或抬起 */
data class TimelineEvent(
    val timeMs: Long,
    val action: Action,
    val keys: List<String>
) {
    enum class Action { DOWN, UP }
}

/** 解析后的谱子 */
data class ParsedScore(
    val name: String,
    val bpm: Int,
    val timeSignature: String,
    val events: List<TimelineEvent>,
    /** 尾音抬键后的结束时刻 */
    val durationMs: Long,
    /** 来源格式：bgi = 文本键盘谱，midi = 谐律键琴事件流，cache = 已展开时间轴 */
    val source: String = "bgi",
    /** 按下次数（音符数，和弦算一个音） */
    val noteCount: Int = 0
)

/** 曲库排序模式（持久化值） */
object LibrarySort {
    const val RECENT = "recent"      // 新旧顺序：文件修改时间降序
    const val NAME = "name"          // 字母顺序：曲名升序（默认）
    const val DURATION = "duration"  // 时长顺序：短 → 长
}

/** 曲库条目（来自 SAF 扫描索引，JSON 持久化） */
data class ScoreIndexEntity(
    val documentId: String,
    val name: String,
    val uriString: String,
    val treeUriString: String,
    val size: Long,
    val modifiedMs: Long,
    val useCache: Boolean = false,
    val starred: Boolean = false,
    val order: Int = 0,
    /** 扫描时解析出的时长 ms；0 = 旧索引尚未生成，点刷新曲库即可补齐 */
    val durationMs: Long = 0L
)
