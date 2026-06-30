package com.obsidian.quickcapture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                handleShare(intent)
                return
            }
            else -> {
                setContent {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.White
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Quick Capture", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A2E))
                            Spacer(Modifier.height(24.dp))
                            Text("在微信中分享内容到此App", fontSize = 16.sp, color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            Text("素材会自动同步到Obsidian", fontSize = 14.sp, color = Color.LightGray)
                        }
                    }
                }
                startDiscovery()
            }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) { finish(); return }
        scope.launch {
            try {
                val content = ContentExtractor.extract(intent)
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
                        finish(); return@launch
                    }
                }
                queue.enqueue(content.title, content.body, content.url, type.frontmatterValue, source, md)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已排队", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }

    private fun startDiscovery() {
        scope.launch {
            MdnsDiscovery.discover(this@MainActivity).collectLatest { server ->
                discoveredServer = server
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
