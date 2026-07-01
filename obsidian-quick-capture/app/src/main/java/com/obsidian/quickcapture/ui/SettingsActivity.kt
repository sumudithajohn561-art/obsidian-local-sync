package com.obsidian.quickcapture.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsidian.quickcapture.InboxDir
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
        setContent { MainUI() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainUI() {
        val inboxDir = remember { InboxDir.find() }
        val inboxOk = inboxDir != null
        var log by remember { mutableStateOf(if (inboxOk) "收件箱就绪" else "未找到收件箱") }
        var input by remember { mutableStateOf("") }
        var savedFiles by remember { mutableStateOf(listOf<File>()) }

        // 刷新已保存的文件列表
        LaunchedEffect(log) {
            savedFiles = inboxDir?.listFiles()
                ?.filter { it.extension == "md" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(20) ?: emptyList()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Quick Capture") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
            ) {
                Text(log, fontSize = 13.sp, color = if (log.contains("✅")) Color(0xFF4CAF50) else Color.Gray)

                Spacer(Modifier.height(8.dp))

                // 输入 + 按钮
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("粘贴链接到这里") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clip.isNotBlank()) { input = clip; log = "已粘贴" } else log = "❌ 剪贴板为空"
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("📋 粘贴")
                    }
                    Button(onClick = {
                        if (input.isNotBlank()) {
                            scope.launch {
                                val ok = doSave(input)
                                if (ok) { input = ""; log = "✅ 已保存 (${savedFiles.size+1}条)" }
                                else log = "❌ 保存失败"
                            }
                        }
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        enabled = input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) { Text("保存", color = Color.White) }
                }

                Spacer(Modifier.height(16.dp))

                // 已保存的文件列表
                if (savedFiles.isNotEmpty()) {
                    Text("最近保存 (${savedFiles.size})", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(savedFiles) { file ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).clickable {
                                        input = file.nameWithoutExtension
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📄", fontSize = 18.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.nameWithoutExtension,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                                .format(file.lastModified()),
                                            fontSize = 11.sp, color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("还没有保存内容", color = Color.LightGray, fontSize = 14.sp)
                    Text("在微信复制链接 → 打开App → 粘贴 → 保存", color = Color.LightGray, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
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
            val dir = InboxDir.find() ?: return@withContext false
            FileWriter(contentResolver, dir).write(content, type, md)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "已保存 ✓", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) { false }
    }
}
