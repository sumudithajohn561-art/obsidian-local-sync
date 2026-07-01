package com.obsidian.quickcapture.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsidian.quickcapture.content.*
import com.obsidian.quickcapture.markdown.*
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var clipText by mutableStateOf("")
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val candidates = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )
        val inboxOk = candidates.any { File(it).exists() }

        clipText = readClipboard()
        if (clipText.isNotBlank() && inboxOk) {
            scope.launch { runSave(clipText); finish() }
        }

        setContent {
            var status by remember { mutableStateOf(if (clipText.isNotBlank()) "saving" else if (!inboxOk) "no_inbox" else "idle") }

            LaunchedEffect(clipText) {
                if (clipText.isNotBlank() && inboxOk) {
                    status = "saving"
                    val ok = runSave(clipText)
                    status = if (ok) "done" else "error"
                    if (ok) delay(1000)
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 大圆圈状态
                    Box(
                        modifier = Modifier.size(100.dp).clip(CircleShape).background(
                            when (status) {
                                "saving" -> Color(0xFF2196F3)
                                "done" -> Color(0xFF4CAF50)
                                "error" -> Color(0xFFFF5722)
                                "no_inbox" -> Color(0xFFFF9800)
                                else -> Color(0xFFE0E0E0)
                            }
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when (status) {
                                "saving" -> "→"
                                "done" -> "✓"
                                "error" -> "✗"
                                "no_inbox" -> "⚙"
                                else -> "📥"
                            },
                            fontSize = 40.sp, color = Color.White
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        when (status) {
                            "saving" -> "保存中..."
                            "done" -> "已保存"
                            "error" -> "保存失败"
                            "no_inbox" -> "请先配置 Syncthing"
                            else -> "收件箱就绪"
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A2E)
                    )

                    Spacer(Modifier.height(8.dp))

                    if (status == "idle") {
                        Text("打开即保存，复制链接后打开App", fontSize = 14.sp, color = Color.Gray)
                    } else if (status == "no_inbox") {
                        Text("安装 Syncthing 并设置同步文件夹", fontSize = 14.sp, color = Color(0xFFFF9800))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val text = readClipboard()
        if (text.isNotBlank() && text != clipText) {
            clipText = text
            scope.launch {
                if (runSave(text)) delay(1000)
            }
        }
    }

    private fun readClipboard(): String = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    } catch (_: Exception) { "" }

    private suspend fun runSave(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = ContentExtractor.extract(android.content.Intent().apply {
                putExtra(android.content.Intent.EXTRA_TEXT, text); type = "text/plain" })
            val type = ContentClassifier.classify(content.mimeType, content.body)
            val source = ContentClassifier.extractSource(content.body)
            val md = MarkdownGenerator.generate(content, type, source)
            val inboxDir = findInboxDir() ?: return@withContext false
            FileWriter(contentResolver, inboxDir).write(content, type, md)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "已保存 ✓", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (_: Exception) { false }
    }

    private fun findInboxDir(): File? {
        for (path in listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )) { val d = File(path); if (d.exists() || d.mkdirs()) return d }
        return null
    }
}
