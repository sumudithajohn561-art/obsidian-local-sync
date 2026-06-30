package com.obsidian.quickcapture.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true

        setContent {
            MaterialTheme { SettingsScreen(hasPermission = hasPermission, onRequestPermission = { requestPermission() }) }
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(hasPermission: Boolean = true, onRequestPermission: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Capture") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // 权限状态
            if (!hasPermission) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("需要存储权限", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Quick Capture 需要「管理所有文件」权限才能写入 Syncthing 同步文件夹。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRequestPermission) { Text("前往授权") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ 权限已授予", style = MaterialTheme.typography.titleSmall)
                        Text("收件箱路径: /storage/emulated/0/Syncthing/Obsidian-Inbox/")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text("使用说明", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = """
                    1. 在微信/浏览器等App中点「分享」
                    2. 在分享菜单中选择「Quick Capture」
                    3. 内容自动保存到 Syncthing 文件夹
                    4. 电脑端 Obsidian 自动接收处理

                    支持: 公众号文章 / 网页链接 / 图片 / 视频链接 / PDF / 纯文字
                    提示: App 没有主界面，直接在分享菜单中使用。
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("版本: 1.0.0 | MIT 开源\n不收集任何数据，所有内容仅存储在本地。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
