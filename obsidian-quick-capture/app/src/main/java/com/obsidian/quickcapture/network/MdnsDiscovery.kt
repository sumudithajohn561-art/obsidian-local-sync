package com.obsidian.quickcapture.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    fun discover(context: Context): Flow<DiscoveredServer> = callbackFlow {
        val nsdManager = try {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        } catch (e: Exception) {
            null
        }

        if (nsdManager == null) {
            awaitClose {}
            return@callbackFlow
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val host = resolvedInfo.host?.hostAddress ?: return
                            trySend(DiscoveredServer(
                                name = resolvedInfo.serviceName,
                                host = host,
                                port = resolvedInfo.port,
                                hostname = resolvedInfo.attributes?.get("hostname")?.toString()
                            ))
                        }
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    })
                } catch (_: Exception) {}
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (_: Exception) {}

        awaitClose {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        }
    }
}
