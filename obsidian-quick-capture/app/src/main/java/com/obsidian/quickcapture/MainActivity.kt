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
                var count = 0
                if (multiple) {
                    intent.clipData?.let { cd ->
                        for (i in 0 until cd.itemCount) {
                            val item = Intent().apply {
                                action = Intent.ACTION_SEND; type = intent.type
                                putExtra(Intent.EXTRA_TEXT, cd.getItemAt(i).text)
                                putExtra(Intent.EXTRA_STREAM, cd.getItemAt(i).uri)
                            }
                            if (save(ContentExtractor.extract(item))) count++
                        }
                    }
                } else {
                    if (save(ContentExtractor.extract(intent))) count++
                }
                val msg = if (count > 0) "已保存 ${if (multiple) count.toString()+"项" else ""}" else "保存失败"
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "失败", Toast.LENGTH_SHORT).show() }
            }
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
