package com.obsidian.quickcapture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.network.HttpSender
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var serverUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverUrl = getSharedPreferences("capture", MODE_PRIVATE).getString("server_url", null)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleShare(intent, false)
            Intent.ACTION_SEND_MULTIPLE -> handleShare(intent, true)
            else -> finish()
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

        // 策略1: 尝试 HTTP 直传（快）
        var httpOk = false
        val url = serverUrl
        if (url != null) {
            val r = HttpSender.sendMarkdown(url, md)
            httpOk = r.success
        }

        // 策略2: 同时写 Syncthing 文件夹（稳，兜底）
        val inboxDir = FileWriter.DEFAULT_INBOX_PATH
        if (inboxDir.exists() || inboxDir.mkdirs()) {
            val writer = FileWriter(contentResolver, inboxDir)
            writer.write(content, type, md)
        }

        val msg = if (httpOk) "已保存 ✓" else "已保存（稍后同步）"
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
