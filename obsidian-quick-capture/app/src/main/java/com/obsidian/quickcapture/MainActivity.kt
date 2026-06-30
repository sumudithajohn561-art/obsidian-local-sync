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
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var queue by lazy { OfflineQueue(this) }
    private var serverState by mutableStateOf<ServerState>(ServerState.Disconnected)
    private var discoveredServer: DiscoveredServer? = null
    private var queueCount by mutableStateOf(0)
    private var recentCaptures by mutableStateOf<List<JSONObject>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动 mDNS 发现
        startDiscovery()

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                handleShare(intent, false)
                return
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                handleShare(intent, true)
                return
            }
            else -> showMainScreen()
        }
    }

    private fun startDiscovery() {
        scope.launch {
            serverState = ServerState.Connecting
            MdnsDiscovery.discover(this@MainActivity).collectLatest { server ->
                discoveredServer = server
                serverState = ServerState.Connected(server)
                flushQueue(server)
            }
        }
        scope.launch {
            delay(5000)
            if (serverState is ServerState.Connecting) {
                serverState = ServerState.Disconnected
            }
        }
        scope.launch {
            while (isActive) {
                queueCount = queue.count()
                recentCaptures = queue.getAll()
                delay(3000)
            }
        }
    }

    private suspend fun flushQueue(server: DiscoveredServer) {
        val sent = queue.flushAll(server.url) { item ->
            HttpSender.send(
                server.url, item.optString("title"), item.optString("body"),
                item.optString("url").ifBlank { null },
                item.optString("source_type"), item.optString("source")
            ).success
        }
        if (sent > 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "已同步 $sent 条离线内容", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleShare(intent: Intent?, isMultiple: Boolean) {
        if (intent == null) { finish(); return }
        scope.launch {
            if (isMultiple) handleMultiple(intent) else {
                processAndSend(ContentExtractor.extract(intent))
            }
            finish()
        }
    }

    private suspend fun processAndSend(content: SharedContent) {
        val contentType = ContentClassifier.classify(content.mimeType, content.body)
        val source = ContentClassifier.extractSource(content.body)
        val markdown = MarkdownGenerator.generate(content, contentType, source)

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

        queue.enqueue(content.title, content.body, content.url, contentType.frontmatterValue, source, markdown)
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "已排队", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun handleMultiple(intent: Intent) {
        val clipData = intent.clipData ?: return
        for (i in 0 until clipData.itemCount) {
            val itemIntent = Intent().apply {
                action = Intent.ACTION_SEND; type = intent.type
                putExtra(Intent.EXTRA_TEXT, clipData.getItemAt(i).text)
                putExtra(Intent.EXTRA_STREAM, clipData.getItemAt(i).uri)
            }
            processAndSend(ContentExtractor.extract(itemIntent))
        }
    }

    private fun showMainScreen() {
        setContent {
            MaterialTheme {
                QuickCaptureScreen(
                    serverState = serverState,
                    queueCount = queueCount,
                    recentCaptures = recentCaptures,
                    onPaste = { text -> scope.launch { handlePaste(text) } },
                    onSettings = {}
                )
            }
        }
    }

    private suspend fun handlePaste(text: String) {
        processAndSend(SharedContent(
            title = if (text.startsWith("http")) "手动粘贴" else text.take(50),
            body = text, url = if (text.startsWith("http")) text else null,
            mimeType = "text/plain", attachmentUri = null, attachmentFileName = null
        ))
        queueCount = queue.count()
        recentCaptures = queue.getAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
