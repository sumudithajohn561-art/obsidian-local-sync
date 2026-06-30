package com.obsidian.quickcapture.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.obsidian.quickcapture.network.MdnsDiscovery
import com.obsidian.quickcapture.network.MdnsDiscovery.DiscoveredServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var serverState by mutableStateOf("搜索中...")
    private var connected by mutableStateOf(false)
    private var serverHost by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scope.launch {
            try {
                MdnsDiscovery.discover(this@SettingsActivity).collectLatest { server ->
                    connected = true
                    serverState = "已连接"
                    serverHost = server.hostname ?: server.host
                }
            } catch (e: Exception) {
                serverState = "mDNS不支持"
            }
        }
        scope.launch {
            delay(5000)
            if (!connected) {
                serverState = "未连接"
            }
        }

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 连接状态指示器
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (connected) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        serverState,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (connected) Color(0xFF2E7D32) else Color.Gray
                    )
                    if (serverHost.isNotEmpty()) {
                        Text(serverHost, fontSize = 13.sp, color = Color.Gray)
                    }
                    Spacer(Modifier.height(32.dp))

                    Text("Quick Capture", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A2E))
                    Spacer(Modifier.height(8.dp))
                    Text("在微信中分享→选择Quick Capture", fontSize = 14.sp, color = Color.LightGray)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
