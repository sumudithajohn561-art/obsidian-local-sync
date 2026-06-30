package com.obsidian.quickcapture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.network.HttpSender
import com.obsidian.quickcapture.network.MdnsDiscovery
import com.obsidian.quickcapture.network.MdnsDiscovery.DiscoveredServer
import com.obsidian.quickcapture.network.OfflineQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val queue by lazy { OfflineQueue(this) }
    private var discoveredServer: DiscoveredServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 读取手动配置的服务器地址
        val savedUrl = getSharedPreferences("capture", MODE_PRIVATE).getString("server_url", null)
        if (savedUrl != null) {
            discoveredServer = DiscoveredServer("saved", savedUrl.replace("http://", "").split(":")[0], 19527)
        }

        // 同时尝试 mDNS
        scope.launch {
            try {
                MdnsDiscovery.discover(this@MainActivity).collectLatest { server ->
                    discoveredServer = server
                }
            } catch (_: Exception) {}
        }

        // 处理分享
        when (intent?.action) {
            Intent.ACTION_SEND -> handleShare(intent, false)
            Intent.ACTION_SEND_MULTIPLE -> handleShare(intent, true)
            else -> {}
        }
    }

    private fun handleShare(intent: Intent?, isMultiple: Boolean) {
        if (intent == null) { finish(); return }
        scope.launch {
            try {
                if (isMultiple) {
                    val clipData = intent.clipData ?: return@launch
                    for (i in 0 until clipData.itemCount) {
                        val itemIntent = Intent().apply {
                            action = Intent.ACTION_SEND; type = intent.type
                            putExtra(Intent.EXTRA_TEXT, clipData.getItemAt(i).text)
                            putExtra(Intent.EXTRA_STREAM, clipData.getItemAt(i).uri)
                        }
                        processOne(ContentExtractor.extract(itemIntent))
                    }
                } else {
                    processOne(ContentExtractor.extract(intent))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }

    private suspend fun processOne(content: com.obsidian.quickcapture.content.SharedContent) {
        val type = ContentClassifier.classify(content.mimeType, content.body)
        val source = ContentClassifier.extractSource(content.body)
        val md = MarkdownGenerator.generate(content, type, source)

        val server = discoveredServer
        if (server != null) {
            val r = HttpSender.sendMarkdown(server.url, md)
            if (r.success) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
        queue.enqueue(content.title, content.body, content.url, type.frontmatterValue, source, md)
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "已排队", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
