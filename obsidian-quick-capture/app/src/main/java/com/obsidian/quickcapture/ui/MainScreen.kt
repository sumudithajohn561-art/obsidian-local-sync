package com.obsidian.quickcapture.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obsidian.quickcapture.network.MdnsDiscovery.DiscoveredServer
import com.obsidian.quickcapture.network.PendingCapture

/**
 * Quick Capture 主屏幕
 *
 * 极简主义设计:
 * - 连接状态 (绿色脉冲点 = 已连接)
 * - 粘贴入口
 * - 最近捕获列表
 * - 设置按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureScreen(
    serverState: ServerState,
    queueCount: Int,
    recentCaptures: List<PendingCapture>,
    onPaste: (String) -> Unit,
    onSettings: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var pasteText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // ========== 连接状态 ==========
            ConnectionIndicator(state = serverState)

            Spacer(modifier = Modifier.height(24.dp))

            // ========== 粘贴入口 ==========
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                placeholder = { Text("粘贴链接到这里...", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (pasteText.isNotBlank()) {
                            onPaste(pasteText)
                            pasteText = ""
                            focusManager.clearFocus()
                        }
                    }
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFFE0E0E0)
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ========== 最近捕获 ==========
            if (recentCaptures.isNotEmpty()) {
                Text(
                    "最近捕获",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 13.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(recentCaptures.take(50)) { item ->
                        CaptureItem(item)
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Text("还没有捕获内容", color = Color.LightGray, fontSize = 15.sp)
                Text("在微信里分享一篇文章试试", color = Color.LightGray, fontSize = 13.sp)
                Spacer(modifier = Modifier.weight(1f))
            }

            // ========== 底部信息 ==========
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (queueCount > 0) {
                    Text(
                        "📤 $queueCount 条待发送",
                        fontSize = 12.sp,
                        color = Color(0xFFFF9800)
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                TextButton(onClick = onSettings) {
                    Text("设置", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }
}

// ========== 连接状态指示器 ==========
@Composable
fun ConnectionIndicator(state: ServerState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 脉冲绿点
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        is ServerState.Connected -> Color(0xFF4CAF50)
                        is ServerState.Connecting -> Color(0xFFFFC107)
                        is ServerState.Disconnected -> Color(0xFFBDBDBD)
                    }
                )
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = when (state) {
                is ServerState.Connected -> "● 已连接"
                is ServerState.Connecting -> "● 搜索中..."
                is ServerState.Disconnected -> "● 未连接"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = when (state) {
                is ServerState.Connected -> Color(0xFF2E7D32)
                is ServerState.Connecting -> Color(0xFFF57F17)
                is ServerState.Disconnected -> Color(0xFF757575)
            }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when (state) {
                is ServerState.Connected -> state.server.hostname ?: state.server.host
                is ServerState.Connecting -> "正在扫描局域网..."
                is ServerState.Disconnected -> "无法找到电脑"
            },
            fontSize = 13.sp,
            color = Color.Gray
        )
    }
}

// ========== 捕获列表项 ==========
@Composable
fun CaptureItem(item: PendingCapture) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF8F8F8)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = item.source,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatTimeAgo(item.createdAt),
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }
        }
    }
}

fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        else -> "${diff / 86400_000}天前"
    }
}

// ========== 服务状态 ==========
sealed class ServerState {
    data class Connected(val server: DiscoveredServer) : ServerState()
    data object Connecting : ServerState()
    data object Disconnected : ServerState()
}
