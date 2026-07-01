package com.obsidian.quickcapture.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsidian.quickcapture.ClipboardService
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
        createNotificationChannel()

        val candidates = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )
        val inboxOk = candidates.any { File(it).exists() }
        val isMonitoring = isServiceRunning()

        val clip = readClipboard()
        if (clip.isNotBlank() && !isMonitoring) {
            scope.launch { saveAndShow(clip) }
        }

        setContent {
            var monitoring by remember { mutableStateOf(isMonitoring) }
            var status by remember { mutableStateOf(if (inboxOk) "ready" else "no_inbox") }
            var savedCount by remember { mutableIntStateOf(0) }

            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 状态圈
                    Box(
                        modifier = Modifier.size(80.dp).clip(CircleShape).background(
                            when {
                                !inboxOk -> Color(0xFFFF9800)
                                monitoring -> Color(0xFF4CAF50)
                                else -> Color(0xFFE0E0E0)
                            }
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when { !inboxOk -> "⚠️"; monitoring -> "👂"; else -> "📥" },
                            fontSize = 32.sp
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        when {
                            !inboxOk -> "收件箱未找到"
                            monitoring -> "正在后台监听剪贴板"
                            else -> "收件箱就绪"
                        },
                        fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A2E)
                    )

                    Spacer(Modifier.height(8.dp))

                    if (!inboxOk) {
                        Text("请确保 Syncthing 已安装并设置同步", fontSize = 13.sp, color = Color(0xFFFF9800))
                    } else if (!monitoring) {
                        Text("复制链接后打开App自动保存", fontSize = 13.sp, color = Color.Gray)
                    }

                    Spacer(Modifier.height(32.dp))

                    // 监听开关
                    if (inboxOk) {
                        Button(
                            onClick = {
                                if (monitoring) stopMonitoring() else startMonitoring()
                                monitoring = !monitoring
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (monitoring) Color(0xFFFF5722) else Color(0xFF4CAF50)
                            )
                        ) {
                            Text(
                                if (monitoring) "停止后台监听" else "开启后台监听",
                                color = Color.White, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (monitoring) "复制链接时会弹出通知询问是否保存" else "开启后，后台自动检测剪贴板内容",
                            fontSize = 12.sp, color = Color.Gray
                        )
                    }

                    if (savedCount > 0) {
                        Spacer(Modifier.height(24.dp))
                        Text("已保存 $savedCount 项", fontSize = 13.sp, color = Color.LightGray)
                    }
                }
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == ClipboardService::class.java.name }
    }

    private fun startMonitoring() {
        createNotificationChannel()
        val intent = Intent(this, ClipboardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        Toast.makeText(this, "后台监听已开启", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, ClipboardService::class.java))
        Toast.makeText(this, "后台监听已停止", Toast.LENGTH_SHORT).show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ClipboardService.CHANNEL_ID, "剪贴板监听",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台剪贴板监听通知" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun readClipboard(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
            cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    private suspend fun saveAndShow(text: String) = withContext(Dispatchers.IO) {
        try {
            val content = SharedContent(
                title = if (text.startsWith("http")) text.take(80) else text.take(50),
                body = text, url = if (text.startsWith("http")) text else null,
                mimeType = "text/plain", attachmentUri = null, attachmentFileName = null
            )
            val type = ContentClassifier.classify(content.mimeType, content.body)
            val source = ContentClassifier.extractSource(content.body)
            val md = MarkdownGenerator.generate(content, type, source)
            val inboxDir = findInboxDir() ?: return@withContext
            FileWriter(contentResolver, inboxDir).write(content, type, md)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun findInboxDir(): File? {
        val candidates = listOf(
            File("/storage/emulated/0/Syncthing/Obsidian-Inbox"),
            File("/storage/emulated/0/Syncthing/obsidian-inbox"),
            File("/sdcard/Syncthing/Obsidian-Inbox"),
        )
        for (dir in candidates) { if (dir.exists() || dir.mkdirs()) return dir }
        return null
    }
}
