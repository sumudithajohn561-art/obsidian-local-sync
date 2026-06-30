package com.obsidian.quickcapture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class SettingsActivity : ComponentActivity() {
    private var inboxOk by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查 Syncthing 文件夹是否存在
        val candidates = listOf(
            "/storage/emulated/0/Syncthing/Obsidian-Inbox",
            "/storage/emulated/0/Syncthing/obsidian-inbox",
            "/sdcard/Syncthing/Obsidian-Inbox"
        )
        inboxOk = candidates.any { File(it).exists() }

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
                        Text("分享内容会自动同步到 Obsidian", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        Text("⚠️ 未找到 Syncthing 文件夹", fontSize = 16.sp, color = Color(0xFFFF9800))
                        Text("请确保 Syncthing 已安装并设置同步", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}
