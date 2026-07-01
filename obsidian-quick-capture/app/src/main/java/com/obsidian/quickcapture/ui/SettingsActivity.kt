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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val candidates = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )
        val inboxOk = candidates.any { File(it).exists() }

        setContent {
            var inputText by remember { mutableStateOf("") }
            var savedMsg by remember { mutableStateOf("") }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

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
                    Spacer(Modifier.height(24.dp))

                    // 输入框
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("链接或文本内容") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4
                    )

                    Spacer(Modifier.height(12.dp))

                    // 两个按钮
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 粘贴按钮
                        OutlinedButton(
                            onClick = {
                                val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (clip.isNotBlank()) inputText = clip
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📋 粘贴", color = Color(0xFF2196F3))
                        }

                        // 保存按钮
                        Button(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    scope.launch {
                                        val result = saveAndReturn(inputText)
                                        savedMsg = if (result) "✅ 已保存" else "❌ 保存失败"
                                        if (result) inputText = ""
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            enabled = inputText.isNotBlank()
                        ) {
                            Text("保存", color = Color.White)
                        }
                    }

                    // 状态消息
                    if (savedMsg.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(savedMsg, fontSize = 14.sp, color = if (savedMsg.startsWith("✅")) Color(0xFF4CAF50) else Color.Red)
                    }
                }
            }
        }
    }

    private suspend fun saveAndReturn(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = SharedContent(
                title = if (text.startsWith("http")) text.take(80) else text.take(50),
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
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
                true
            } else false
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
