package com.obsidian.quickcapture.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.obsidian.quickcapture.CaptureRepository
import com.obsidian.quickcapture.InboxDir
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App() {
        val ready = remember { CaptureRepository.isReady() }
        var input by remember { mutableStateOf("") }
        var status by remember { mutableStateOf(if (ready) "收件箱就绪" else "❌ 收件箱未找到") }
        var files by remember { mutableStateOf(CaptureRepository.listFiles()) }

        fun refresh() { files = CaptureRepository.listFiles() }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Quick Capture") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)) }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                Text(status, fontSize = 13.sp, color = if (status.startsWith("❌")) Color.Red else Color(0xFF4CAF50))
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(value = input, onValueChange = { input = it },
                    label = { Text("粘贴链接到这里") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clip.isNotBlank()) { input = clip; status = "已粘贴 ${clip.length}字" }
                        else status = "❌ 剪贴板为空"
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("📋 粘贴")
                    }
                    Button(onClick = {
                        if (input.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val name = CaptureRepository.saveText(input, contentResolver)
                                    withContext(Dispatchers.Main) {
                                        if (name != null) {
                                            input = ""
                                            status = "✅ 已保存: $name"
                                            Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                                            refresh()
                                        } else status = "❌ 保存失败\n${InboxDir.diagnostic()}"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        status = "❌ ${e.message}"
                                    }
                                }
                            }
                        }
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        enabled = input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) { Text("保存", color = Color.White) }
                }

                Spacer(Modifier.height(16.dp))
                if (files.isNotEmpty()) {
                    Text("保存记录 (${files.size})", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(files) { file ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(file.nameWithoutExtension, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(file.lastModified()),
                                        fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("还没有保存内容", color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
