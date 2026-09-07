package com.watchmen.tracker

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages zero-configuration network service discovery (DNS-SD / mDNS) on LAN
 * to automatically locate the FastAPI backend server when IP addresses change.
 *
 * Discovers service type "_watchmen._tcp." advertised by the backend via zeroconf.
 */
class BackendDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "BackendDiscoveryManager"
        private const val SERVICE_TYPE = "_watchmen._tcp"
        private const val BASE_RETRY_DELAY_MS = 5000L
        private const val MAX_RETRY_DELAY_MS = 60000L
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val isDiscovering = AtomicBoolean(false)
    private val isResolving = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = BASE_RETRY_DELAY_MS

    fun startDiscovery() {
        if (isDiscovering.getAndSet(true)) return

        Log.i(TAG, "[DISCOVERY] Starting discovery")
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("WatchmenDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
                Log.d(TAG, "Acquired WifiManager.MulticastLock for mDNS discovery")
            }

            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                Log.w(TAG, "NsdManager not available on this device")
                isDiscovering.set(false)
                return
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "DISCOVERY_START_FAILED: code $errorCode")
                    stopDiscovery()
                    scheduleRetry()
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "DISCOVERY_STOP_FAILED: code $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.i(TAG, "WS DEBUG: discovery started for $serviceType")
                    retryDelayMs = BASE_RETRY_DELAY_MS
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    Log.i(TAG, "DISCOVERY_STOPPED")
                    isDiscovering.set(false)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    Log.i(TAG, "SERVICE_FOUND: ${serviceInfo.serviceName} | type: ${serviceInfo.serviceType}")

                    if (serviceInfo.serviceType.contains("_watchmen")) {
                        resolveServiceSafely(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    Log.w(TAG, "SERVICE_LOST: ${serviceInfo?.serviceName}")
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate mDNS discovery: ${e.message}", e)
            isDiscovering.set(false)
            scheduleRetry()
        }
    }

    private fun resolveServiceSafely(serviceInfo: NsdServiceInfo) {
        if (!isResolving.compareAndSet(false, true)) {
            Log.d(TAG, "Resolution currently active for another service, skipping concurrent resolve")
            return
        }

        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "SERVICE_RESOLVE_FAILED: code $errorCode for ${info?.serviceName}")
                    isResolving.set(false)
                }

                override fun onServiceResolved(info: NsdServiceInfo?) {
                    try {
                        val rawHost = info?.host?.hostAddress
                        val port = info?.port ?: 8000

                        if (!rawHost.isNullOrBlank()) {
                            val cleanHost = rawHost.trimStart('/').substringBefore('%')
                            if (cleanHost != "0.0.0.0") {
                                val resolvedUrl = "http://$cleanHost:$port"
                                Log.i(TAG, "[DISCOVERY] Service resolved: $resolvedUrl")
                                Log.i(TAG, "WS DEBUG: discovery resolved host/port: $cleanHost:$port -> $resolvedUrl")
                                BackendEndpointManager.updateEndpoint(resolvedUrl, context)
                            }
                        }
                    } finally {
                        isResolving.set(false)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking resolveService: ${e.message}")
            isResolving.set(false)
        }
    }

    private fun scheduleRetry() {
        handler.postDelayed({
            Log.i(TAG, "RETRY_DISCOVERY scheduled after ${retryDelayMs}ms")
            startDiscovery()
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }, retryDelayMs)
    }

    fun stopDiscovery() {
        if (!isDiscovering.getAndSet(false)) return
        try {
            if (discoveryListener != null && nsdManager != null) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery: ${e.message}")
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                    Log.d(TAG, "Released WifiManager.MulticastLock")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
            }
            multicastLock = null
            discoveryListener = null
            isResolving.set(false)
        }
    }
}
