package com.obsidian.quickcapture.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP 直传——把 Markdown 内容 POST 到电脑上的 local-server
 *
 * 超时设为 3 秒——局域网内应该 < 0.5 秒完成
 */
object HttpSender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class SendResult(val success: Boolean, val fileName: String? = null, val error: String? = null)

    /**
     * 发送一条捕获内容到电脑
     *
     * @param serverUrl 服务端地址，格式: http://192.168.x.x:19527
     * @param title 标题
     * @param body 正文
     * @param url 原始链接
     * @param sourceType 内容类型
     * @param source 来源域名
     */
    suspend fun send(
        serverUrl: String,
        title: String,
        body: String,
        url: String?,
        sourceType: String,
        source: String
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("url", url ?: "")
                put("sourceType", sourceType)
                put("source", source)
            }

            val requestBody = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$serverUrl/capture")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respJson = JSONObject(response.body?.string() ?: "")
                SendResult(success = true, fileName = respJson.optString("file"))
            } else {
                SendResult(success = false, error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            SendResult(success = false, error = e.message ?: "网络错误")
        }
    }

    /**
     * 发送已生成的完整 Markdown 文本
     */
    suspend fun sendMarkdown(serverUrl: String, markdownContent: String): SendResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("title", "手动粘贴")
                put("body", markdownContent)
                put("sourceType", "link")
                put("source", "manual")
            }

            val requestBody = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$serverUrl/capture")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                SendResult(success = true)
            } else {
                SendResult(success = false, error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            SendResult(success = false, error = e.message ?: "网络错误")
        }
    }
}
