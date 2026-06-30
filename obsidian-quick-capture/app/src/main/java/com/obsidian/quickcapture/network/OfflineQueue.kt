package com.obsidian.quickcapture.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 离线队列——用 JSON 文件做持久化，零外部依赖
 * 文件位置: filesDir/pending_captures.json
 */
class OfflineQueue(context: Context) {

    private val file = File(context.filesDir, "pending_captures.json")

    /** 加入离线队列 */
    suspend fun enqueue(
        title: String, body: String, url: String?,
        sourceType: String, source: String, markdownContent: String
    ) = withContext(Dispatchers.IO) {
        val items = readAll().toMutableList()
        items.add(JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("title", title)
            put("body", body)
            put("url", url ?: "")
            put("source_type", sourceType)
            put("source", source)
            put("markdown_content", markdownContent)
            put("created_at", System.currentTimeMillis())
            put("retry_count", 0)
        })
        writeFile(JSONArray(items))
    }

    /** 获取所有待发送项 */
    suspend fun getAll(): List<JSONObject> = withContext(Dispatchers.IO) {
        readAll()
    }

    /** 队列数量 */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        readAll().size
    }

    /** 发送成功后删除 */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        writeFile(JSONArray(readAll().filter { it.optString("id") != id }))
    }

    /** 尝试发送队列中所有项 */
    suspend fun flushAll(serverUrl: String, sender: suspend (JSONObject) -> Boolean): Int = withContext(Dispatchers.IO) {
        var sent = 0
        val items = readAll().toMutableList()
        val remaining = mutableListOf<JSONObject>()

        for (item in items) {
            if (sender(item)) {
                sent++
            } else {
                item.put("retry_count", item.optInt("retry_count") + 1)
                remaining.add(item)
            }
        }
        writeFile(JSONArray(remaining.takeLast(500))) // 最多保留500条
        sent
    }

    private fun readAll(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeFile(arr: JSONArray) {
        file.writeText(arr.toString(2))
    }
}
