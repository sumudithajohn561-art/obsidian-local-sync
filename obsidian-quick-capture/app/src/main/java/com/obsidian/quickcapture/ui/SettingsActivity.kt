package com.obsidian.quickcapture.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.obsidian.quickcapture.content.SharedContent
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    private var inboxOk by mutableStateOf(false)
    private var savedMsg by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val candidates = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )
        inboxOk = candidates.any { File(it).exists() }

        // 自动检测剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Quick Capture", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A2E))
                    Spacer(Modifier.height(16.dp))

                    if (inboxOk) {
                        Text("✅ 收件箱就绪", fontSize = 16.sp, color = Color(0xFF4CAF50))
                    } else {
                        Text("⚠️ 未找到 Syncthing 文件夹", fontSize = 16.sp, color = Color(0xFFFF9800))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("分享内容到App，Syncthing自动同步", fontSize = 13.sp, color = Color.Gray)

                    Spacer(Modifier.height(32.dp))

                    // 粘贴保存区
                    if (clipText.isNotBlank()) {
                        OutlinedTextField(
                            value = clipText,
                            onValueChange = {},
                            label = { Text("剪贴板内容") },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { scope.launch { saveClipboard(clipText) } },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Text(if (clipText.startsWith("http")) "保存链接" else "保存内容", color = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text("复制链接后打开App，自动识别", fontSize = 13.sp, color = Color.LightGray)
                    }

                    if (savedMsg.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(savedMsg, fontSize = 14.sp, color = Color(0xFF4CAF50))
                    }
                }
            }
        }
    }

    private suspend fun saveClipboard(text: String) = withContext(Dispatchers.IO) {
        try {
            val content = SharedContent(
                title = if (text.startsWith("http")) "手动保存 ${text.take(40)}" else text.take(50),
                body = text,
                url = if (text.startsWith("http")) text else null,
                mimeType = "text/plain",
                attachmentUri = null,
                attachmentFileName = null
            )
            val type = ContentClassifier.classify(content.mimeType, content.body)
            val source = ContentClassifier.extractSource(content.body)
            val md = MarkdownGenerator.generate(content, type, source)

            val inboxDir = findInboxDir()
            if (inboxDir != null) {
                FileWriter(contentResolver, inboxDir).write(content, type, md)
                withContext(Dispatchers.Main) {
                    savedMsg = "✅ 已保存"
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                savedMsg = "保存失败: ${e.message}"
            }
        }
    }

    private fun findInboxDir(): File? {
        val candidates = listOf(
            File("/storage/emulated/0/Syncthing/Obsidian-Inbox"),
            File("/storage/emulated/0/Syncthing/obsidian-inbox"),
            File("/sdcard/Syncthing/Obsidian-Inbox"),
        )
        for (dir in candidates) {
            if ((dir.exists() && dir.isDirectory) || dir.mkdirs()) return dir
        }
        val fallback = filesDir.resolve("inbox")
        return if (fallback.mkdirs()) fallback else null
    }
}
