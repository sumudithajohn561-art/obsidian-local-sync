package com.obsidian.quickcapture.ui

import android.os.Bundle
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
import com.obsidian.quickcapture.network.HttpSender
import com.obsidian.quickcapture.network.MdnsDiscovery
import com.obsidian.quickcapture.network.MdnsDiscovery.DiscoveredServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SettingsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var connected by mutableStateOf(false)
    private var statusText by mutableStateOf("搜索中...")
    private var serverHost by mutableStateOf("")
    private var discoveredUrl by mutableStateOf("")
    private var manualIP by mutableStateOf("172.30.144.1:19527")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // mDNS 发现（可能不工作，但不影响功能）
        scope.launch {
            try {
                MdnsDiscovery.discover(this@SettingsActivity).collectLatest { server ->
                    discoveredUrl = server.url
                    testConnection(server.url)
                }
            } catch (_: Exception) {}
        }
        scope.launch {
            delay(5000)
            if (!connected && discoveredUrl.isEmpty()) {
                statusText = "未连接"
            }
        }

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 状态
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (connected) Color(0xFF4CAF50) else Color(0xFFBDBDBD)))
                    Spacer(Modifier.height(12.dp))
                    Text(statusText, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = if (connected) Color(0xFF2E7D32) else Color.Gray)
                    if (serverHost.isNotEmpty()) Text(serverHost, fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(32.dp))

                    // 手动连接
                    OutlinedTextField(
                        value = manualIP,
                        onValueChange = { manualIP = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("IP:端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { testConnection(ensureHttp(manualIP)) } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) { Text("测试连接") }

                    Spacer(Modifier.height(32.dp))
                    Text("Quick Capture v2", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A2E))
                    Text("微信分享 → 选择Quick Capture", fontSize = 13.sp, color = Color.LightGray)
                }
            }
        }
    }

    private suspend fun testConnection(url: String) {
        try {
            val resp = HttpSender.send(url, "test", "test", null, "plain", "local")
            if (resp.success || resp.error?.contains("HTTP") == true) {
                // HTTP 200 或 400（empty content也算连通）
                connected = true; statusText = "已连接"; serverHost = url
                // 保存到 SharedPreferences 供 MainActivity 使用
                getSharedPreferences("capture", MODE_PRIVATE).edit().putString("server_url", url).apply()
            } else {
                connected = false; statusText = "连接失败"
            }
        } catch (e: Exception) {
            connected = false; statusText = "连接失败: ${e.message}"
        }
    }

    private fun ensureHttp(addr: String): String {
        val a = addr.trim()
        return if (a.startsWith("http")) a else "http://$a"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
