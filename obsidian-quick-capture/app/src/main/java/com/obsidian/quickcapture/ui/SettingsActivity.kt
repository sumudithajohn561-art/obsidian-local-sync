package com.obsidian.quickcapture.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
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
import com.obsidian.quickcapture.content.ContentClassifier
import com.obsidian.quickcapture.content.ContentExtractor
import com.obsidian.quickcapture.content.SharedContent
import com.obsidian.quickcapture.markdown.MarkdownGenerator
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val candidates = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )
        val inboxOk = candidates.any { File(it).exists() }

        // 自动读剪贴板
        val clip = readClipboardNow()

        setContent {
            var status by remember { mutableStateOf(if (clip.isNotBlank()) "saving" else "ready") }
            var savedCount by remember { mutableStateOf(0) }

            // 有剪贴板内容 → 自动保存
            LaunchedEffect(clip) {
                if (clip.isNotBlank()) {
                    val ok = saveOne(clip)
                    status = if (ok) "saved" else "error"
                    if (ok) savedCount++
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 状态圈 + 图标
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                when (status) {
                                    "saved" -> Color(0xFF4CAF50)
                                    "saving" -> Color(0xFF2196F3)
                                    "error" -> Color(0xFFFF5722)
                                    else -> Color(0xFFE0E0E0)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when (status) {
                                "saved" -> "✓"
                                "saving" -> "→"
                                "error" -> "✗"
                                else -> "📥"
                            },
                            fontSize = 32.sp,
                            color = Color.White
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        when (status) {
                            "saved" -> "已保存"
                            "saving" -> "正在保存..."
                            "error" -> "保存失败"
                            else -> if (inboxOk) "收件箱就绪" else "收件箱未找到"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A2E)
                    )

                    if (status == "ready" && inboxOk) {
                        Spacer(Modifier.height(8.dp))
                        Text("复制链接后打开App，自动保存", fontSize = 13.sp, color = Color.Gray)
                    }

                    if (!inboxOk) {
                        Spacer(Modifier.height(8.dp))
                        Text("请确保 Syncthing 已安装并同步", fontSize = 13.sp, color = Color(0xFFFF9800))
                    }

                    if (savedCount > 0) {
                        Spacer(Modifier.height(32.dp))
                        Text("已保存 $savedCount 项", fontSize = 13.sp, color = Color.LightGray)
                    }

                    // 短暂状态后重置
                    if (status == "saved" || status == "error") {
                        LaunchedEffect(status) {
                            delay(2000)
                            status = "ready"
                        }
                    }
                }
            }
        }
    }

    private fun readClipboardNow(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    private suspend fun saveOne(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = SharedContent(
                title = if (text.startsWith("http")) text.take(80) else text.take(50),
                body = text, url = if (text.startsWith("http")) text else null,
                mimeType = "text/plain", attachmentUri = null, attachmentFileName = null
            )
            val type = ContentClassifier.classify(content.mimeType, content.body)
            val source = ContentClassifier.extractSource(content.body)
            val md = MarkdownGenerator.generate(content, type, source)
            val inboxDir = findInboxDir() ?: return@withContext false
            FileWriter(contentResolver, inboxDir).write(content, type, md)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) { false }
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
