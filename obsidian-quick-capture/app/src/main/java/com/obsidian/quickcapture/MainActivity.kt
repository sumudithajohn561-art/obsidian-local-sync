package com.obsidian.quickcapture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.content.SharedContent
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.network.HttpSender
import com.obsidian.quickcapture.network.MdnsDiscovery
import com.obsidian.quickcapture.network.MdnsDiscovery.DiscoveredServer
import com.obsidian.quickcapture.network.OfflineQueue
import com.obsidian.quickcapture.ui.QuickCaptureScreen
import com.obsidian.quickcapture.ui.ServerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var queue: OfflineQueue
    private var serverState by mutableStateOf<ServerState>(ServerState.Disconnected)
    private var discoveredServer: DiscoveredServer? = null
    private var queueCount by mutableStateOf(0)
    private var recentCaptures by mutableStateOf<List<com.obsidian.quickcapture.network.PendingCapture>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = OfflineQueue(this)

        // 启动 mDNS 发现
        startDiscovery()

        when (intent?.action) {
            Intent.ACTION_SEND -> handleShare(intent, false)
            Intent.ACTION_SEND_MULTIPLE -> handleShare(intent, true)
            else -> showMainScreen()
        }
    }

    // ========== mDNS 发现 ==========
    private fun startDiscovery() {
        scope.launch {
            serverState = ServerState.Connecting
            MdnsDiscovery.discover(this@MainActivity).collectLatest { server ->
                discoveredServer = server
                serverState = ServerState.Connected(server)
                // 发现电脑后，尝试发送离线队列
                flushQueue(server)
            }
        }
        // 5秒后仍未发现，标记为未连接
        scope.launch {
            delay(5000)
            if (serverState is ServerState.Connecting) {
                serverState = ServerState.Disconnected
            }
        }
        // 刷新队列信息
        scope.launch {
            while (isActive) {
                queueCount = queue.count()
                recentCaptures = queue.getAll()
                delay(3000)
            }
        }
    }

    private suspend fun flushQueue(server: DiscoveredServer) {
        val sent = queue.flushAll(server.url)
        if (sent > 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "已同步 $sent 条离线内容", Toast.LENGTH_SHORT).show()
            }
            queueCount = queue.count()
            recentCaptures = queue.getAll()
        }
    }

    // ========== 分享处理 ==========
    private fun handleShare(intent: Intent?, isMultiple: Boolean) {
        if (intent == null) { finish(); return }

        scope.launch {
            if (isMultiple) {
                handleMultiple(intent)
            } else {
                val content = ContentExtractor.extract(intent)
                processAndSend(content)
            }
            finish()
        }
    }

    private suspend fun processAndSend(content: SharedContent) {
        // 分类
        val contentType = ContentClassifier.classify(content.mimeType, content.body)
        val source = ContentClassifier.extractSource(content.body)

        // 生成 Markdown（无论哪种传输方式都需要）
        val markdown = MarkdownGenerator.generate(content, contentType, source)

        // 策略: HTTP 优先 → 失败则离线队列
        val server = discoveredServer
        if (server != null) {
            val result = HttpSender.sendMarkdown(server.url, markdown)
            if (result.success) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        // HTTP 失败 → 加入离线队列
        queue.enqueue(
            title = content.title,
            body = content.body,
            url = content.url,
            sourceType = contentType.frontmatterValue,
            source = source,
            markdownContent = markdown
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "已排队，连接WiFi后自动发送", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun handleMultiple(intent: Intent) {
        val clipData = intent.clipData ?: return
        var count = 0
        for (i in 0 until clipData.itemCount) {
            val itemIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = intent.type
                putExtra(Intent.EXTRA_TEXT, clipData.getItemAt(i).text)
                putExtra(Intent.EXTRA_STREAM, clipData.getItemAt(i).uri)
            }
            processAndSend(ContentExtractor.extract(itemIntent))
            count++
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "已处理 $count 项", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 主屏幕 ==========
    private fun showMainScreen() {
        setContent {
            MaterialTheme {
                QuickCaptureScreen(
                    serverState = serverState,
                    queueCount = queueCount,
                    recentCaptures = recentCaptures,
                    onPaste = { text -> scope.launch { handlePaste(text) } },
                    onSettings = { /* 滚动到底部即可 */ }
                )
            }
        }
    }

    private suspend fun handlePaste(text: String) {
        val content = SharedContent(
            title = if (text.startsWith("http")) "手动粘贴" else text.take(50),
            body = text,
            url = if (text.startsWith("http")) text else null,
            mimeType = "text/plain",
            attachmentUri = null,
            attachmentFileName = null
        )
        processAndSend(content)
        queueCount = queue.count()
        recentCaptures = queue.getAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
