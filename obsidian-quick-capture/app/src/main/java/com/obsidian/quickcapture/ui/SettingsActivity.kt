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
import com.obsidian.quickcapture.content.*
import com.obsidian.quickcapture.markdown.*
import com.obsidian.quickcapture.storage.FileWriter
import kotlinx.coroutines.*
import java.io.File

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inboxOk = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        ).any { File(it).exists() }

        setContent {
            var log by remember { mutableStateOf(if (inboxOk) "收件箱就绪" else "未找到收件箱") }
            var input by remember { mutableStateOf("") }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Quick Capture", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A2E))
                Spacer(Modifier.height(4.dp))
                Text(log, fontSize = 14.sp, color = if (log.startsWith("✅")) Color(0xFF4CAF50) else if (log.startsWith("❌")) Color.Red else Color.Gray)
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("粘贴链接到这里") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (clip.isNotBlank()) {
                                input = clip
                                log = "已粘贴 (${clip.length}字)"
                            } else {
                                log = "❌ 剪贴板为空"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("📋 粘贴") }

                    Button(
                        onClick = {
                            if (input.isNotBlank()) {
                                scope.launch {
                                    log = "保存中..."
                                    val ok = doSave(input)
                                    if (ok) { input = ""; log = "✅ 已保存" }
                                    else log = "❌ 保存失败"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        enabled = input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) { Text("保存", color = Color.White) }
                }
            }
        }
    }

    private suspend fun doSave(text: String): Boolean = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) { false }
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
