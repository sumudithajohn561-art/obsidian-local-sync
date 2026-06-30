package com.obsidian.quickcapture.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * mDNS 自动发现局域网内的 Obsidian Capture Server
 *
 * 使用 Android NSD (Network Service Discovery) API。
 * 等价于 Bonjour/Zeroconf 协议，自动扫描局域网内广播
 * "_obsidian-capture._tcp" 服务的电脑。
 */
object MdnsDiscovery {

    private const val SERVICE_TYPE = "_obsidian-capture._tcp"

    data class DiscoveredServer(
        val name: String,
        val host: String,
        val port: Int,
        val hostname: String? = null
    ) {
        val url: String get() = "http://$host:$port"
    }

    /**
     * 开始扫描局域网内的 Capture Server
     *
     * @return Flow<DiscoveredServer> — 每次发现新服务器时发射
     */
    fun discover(context: Context): Flow<DiscoveredServer> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // 发现服务 → 解析详细信息
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val server = DiscoveredServer(
                            name = resolvedInfo.serviceName,
                            host = resolvedInfo.host?.hostAddress ?: return,
                            port = resolvedInfo.port,
                            hostname = resolvedInfo.attributes?.get("hostname")?.toString()
                        )
                        trySend(server)
                    }
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        }
    }
}
