package com.obsidian.quickcapture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 简单的设置界面
 *
 * 目前主要是信息展示和说明，后续可扩展:
 * - 自定义收件箱路径
 * - 文件名模板
 * - 默认标签
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Capture") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "使用说明",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = """
                    1. 在微信/浏览器等App中点「分享」
                    2. 在分享菜单中选择「Quick Capture」
                    3. 内容会自动保存为 Markdown 文件
                    4. Syncthing 将文件同步到电脑
                    5. 在 Obsidian 中查看和处理

                    支持的内容类型:
                    - 公众号文章 / 网页链接
                    - 图片
                    - 视频链接 (B站/YouTube等)
                    - PDF / PPT / Word 等文件
                    - 纯文字内容

                    提示: App 没有主界面，直接在分享菜单中使用即可。
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Divider()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "关于",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = """
                    版本: 1.0.0
                    许可: MIT 开源
                    隐私: 不收集任何数据，所有内容仅存储在本地 Syncthing 文件夹中。
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
