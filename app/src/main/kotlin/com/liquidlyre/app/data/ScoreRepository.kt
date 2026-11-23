package com.liquidlyre.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 曲库仓库：SAF 多目录持久化授权 + 异步扫描 + DataStore(JSON) 索引缓存。
 * - takePersistableUriPermission 已在添加目录时完成，重启授权仍有效
 * - 兼容两种结构：① .json 直接在所选目录根；② 选中的是 BGI score_file 父目录，自动进 score_file/ 与 cache/
 * - 只读、容错：坏文件记录到诊断，不中断扫描；不申请读外部存储权限
 */
class ScoreRepository(private val context: Context) {

    val prefs = UserPrefs(context)

    private val indexFlow: Flow<List<ScoreIndexEntity>> = prefs.rawIndex.map { parseIndex(it) }

    /** 排序模式变化即时重排，无需重新扫描；星标始终置顶 */
    val allEntries: Flow<List<ScoreIndexEntity>> = combine(indexFlow, prefs.sortMode) { list, mode ->
        sortEntries(list, mode)
    }

    fun search(q: String): Flow<List<ScoreIndexEntity>> = allEntries.map { list ->
        if (q.isBlank()) list else list.filter { it.name.contains(q, true) }
    }

    suspend fun allList(): List<ScoreIndexEntity> = allEntries.first()

    data class TreeDir(val uri: String, val name: String, val valid: Boolean)
    data class ScanResult(val added: Int, val errors: List<String>)

    fun checkTree(uriString: String): TreeDir = try {
        val uri = Uri.parse(uriString)
        val persisted = context.contentResolver.getPersistedUriPermissions()
            .any { it.uri.toString() == uriString && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        TreeDir(uriString, root?.name ?: uriString, persisted && root != null && root.exists())
    } catch (_: Throwable) {
        TreeDir(uriString, uriString, false)
    }

    fun listTrees(): List<TreeDir> = kotlinx.coroutines.runBlocking {
        prefs.treeUris.first().map { checkTree(it) }
    }

    fun readContent(uriString: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (_: Throwable) { null }

    /** 扫描全部目录，重建索引（IO 线程池，严禁主线程）。starred 状态按 documentId 保留。 */
    suspend fun scanAll(): ScanResult = withContext(Dispatchers.IO) {
        val trees = prefs.treeUris.first()
        val errors = ArrayList<String>()
        val collected = ArrayList<ScoreIndexEntity>()
        val cacheFast = prefs.cacheFast.first()
        val oldStars = allList().associate { it.documentId to it.starred }

        for ((treeIdx, treeUriStr) in trees.withIndex()) {
            val root = try { DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) } catch (_: Throwable) { null }
            if (root == null || !root.exists()) {
                errors += "目录不可访问：$treeUriStr"
                continue
            }
            val scoreRoot = root.findFile("score_file") ?: root
            val cacheRoot = root.findFile("cache")
            var order = 0

            scoreRoot.listFiles()
                .filter { it.isFile && it.name?.endsWith(".json", true) == true }
                .forEach { doc ->
                    try {
                        collected += ScoreIndexEntity(
                            documentId = doc.uri.toString(),
                            name = doc.name ?: "未命名",
                            uriString = doc.uri.toString(),
                            treeUriString = treeUriStr,
                            size = doc.length(),
                            modifiedMs = doc.lastModified(),
                            useCache = false,
                            starred = oldStars[doc.uri.toString()] ?: false,
                            order = treeIdx * 10000 + order,
                            durationMs = durationOf(doc.uri.toString(), doc.name ?: "未命名")
                        )
                    } catch (t: Throwable) {
                        errors += "跳过 ${doc.name}: ${t.message ?: "读取失败"}"
                    }
                    order++
                }

            if (cacheFast && cacheRoot != null && cacheRoot.exists()) {
                cacheRoot.listFiles()
                    .filter { it.isFile && it.name?.endsWith(".json", true) == true }
                    .forEach { doc ->
                        collected += ScoreIndexEntity(
                            documentId = doc.uri.toString(),
                            name = (doc.name ?: "未命名").removeSuffix(".json"),
                            uriString = doc.uri.toString(),
                            treeUriString = treeUriStr,
                            size = doc.length(),
                            modifiedMs = doc.lastModified(),
                            useCache = true,
                            starred = oldStars[doc.uri.toString()] ?: false,
                            order = treeIdx * 10000 + order,
                            durationMs = durationOf(doc.uri.toString(), doc.name ?: "未命名")
                        )
                        order++
                    }
            }
        }

        prefs.setIndex(serializeIndex(collected))
        ScanResult(collected.size, errors)
    }

    /** 扫描期一次性解析谱面拿时长（IO 线程，几 KB 的 JSON 开销可忽略） */
    private fun durationOf(uriString: String, name: String): Long {
        val content = readContent(uriString) ?: return 0L
        return try {
            ScoreFileParser.parse(name, content).score?.durationMs ?: 0L
        } catch (_: Throwable) { 0L }
    }

    suspend fun deleteTree(treeUri: String) {
        val cur = allList().filter { it.treeUriString != treeUri }
        prefs.setIndex(serializeIndex(cur))
    }

    suspend fun setStarred(id: String, starred: Boolean) {
        val cur = allList().map { if (it.documentId == id) it.copy(starred = starred) else it }
        prefs.setIndex(serializeIndex(cur))
    }

    suspend fun loadScore(entry: ScoreIndexEntity): ParsedScore? = withContext(Dispatchers.IO) {
        val content = readContent(entry.uriString)
        if (content == null) {
            com.liquidlyre.app.engine.PlayerState.diag("读不到文件：${entry.name}（授权失效？重选目录）")
            return@withContext null
        }
        val r = ScoreFileParser.parse(entry.name, content)
        r.error?.let { com.liquidlyre.app.engine.PlayerState.diag("${entry.name}：$it") }
        r.score
    }

    // ---- JSON 序列化 ----
    private fun serializeIndex(list: List<ScoreIndexEntity>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("documentId", e.documentId); put("name", e.name)
                put("uriString", e.uriString); put("treeUriString", e.treeUriString)
                put("size", e.size); put("modifiedMs", e.modifiedMs)
                put("useCache", e.useCache); put("starred", e.starred); put("order", e.order)
                put("durationMs", e.durationMs)
            })
        }
        return arr.toString()
    }

    private fun parseIndex(raw: String): List<ScoreIndexEntity> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ScoreIndexEntity(
                documentId = o.optString("documentId"),
                name = o.optString("name"),
                uriString = o.optString("uriString"),
                treeUriString = o.optString("treeUriString"),
                size = o.optLong("size"),
                modifiedMs = o.optLong("modifiedMs"),
                useCache = o.optBoolean("useCache"),
                starred = o.optBoolean("starred"),
                order = o.optInt("order"),
                durationMs = o.optLong("durationMs")
            )
        }
    } catch (_: Throwable) { emptyList() }

    companion object {
        /** 纯函数排序，供单测直接验证三种模式 */
        fun sortEntries(list: List<ScoreIndexEntity>, mode: String): List<ScoreIndexEntity> {
            val star = compareByDescending<ScoreIndexEntity> { it.starred }
            return when (mode) {
                LibrarySort.RECENT -> list.sortedWith(
                    star.thenByDescending { it.modifiedMs }.thenBy { it.order }.thenBy { it.name }
                )
                LibrarySort.DURATION -> list.sortedWith(
                    // 时长未知的（旧索引）沉底，其余短 → 长
                    star.thenBy { it.durationMs == 0L }.thenBy { it.durationMs }.thenBy { it.name }
                )
                else -> list.sortedWith(star.thenBy { it.name }.thenBy { it.order })
            }
        }
    }
}
