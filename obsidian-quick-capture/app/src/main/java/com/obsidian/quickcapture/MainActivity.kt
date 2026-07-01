package com.obsidian.quickcapture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> handle(intent, false)
            Intent.ACTION_SEND_MULTIPLE -> handle(intent, true)
            else -> finish()
        }
    }

    private fun handle(intent: Intent?, multiple: Boolean) {
        if (intent == null) { finish(); return }
        scope.launch {
            try {
                val content = ContentExtractor.extract(intent)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "接收: ${content.title.take(30)}...", Toast.LENGTH_SHORT).show()
                }
                if (save(content)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "✅ 已保存: ${content.title.take(20)}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ 保存失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            delay(500)
            finish()
        }
    }

    private suspend fun save(content: com.obsidian.quickcapture.content.SharedContent): Boolean = withContext(Dispatchers.IO) {
        try {
            val type = ContentClassifier.classify(content.mimeType, content.body)
            val source = ContentClassifier.extractSource(content.body)
            val md = MarkdownGenerator.generate(content, type, source)

            // 找 Syncthing 文件夹
            val inboxDir = findInboxDir() ?: return@withContext false
            val writer = FileWriter(contentResolver, inboxDir)
            writer.write(content, type, md)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun findInboxDir(): File? {
        // 按优先级尝试常见路径
        val candidates = listOf(
            File("/storage/emulated/0/Syncthing/Obsidian-Inbox"),
            File("/storage/emulated/0/Syncthing/obsidian-inbox"),
            File("/sdcard/Syncthing/Obsidian-Inbox"),
            File("/storage/emulated/0/Obsidian-Inbox"),
        )
        for (dir in candidates) {
            if ((dir.exists() && dir.isDirectory) || dir.mkdirs()) {
                return dir
            }
        }
        val fallback = filesDir.resolve("inbox")
        return if (fallback.mkdirs()) fallback else null
    }
}
