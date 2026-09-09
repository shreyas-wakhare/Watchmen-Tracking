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
 * Discovers service type "_watchmen._tcp" advertised by the backend via zeroconf.
 */
class BackendDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "BackendDiscoveryManager"
        private const val SERVICE_TYPE = "_watchmen._tcp"
        private const val BASE_RETRY_DELAY_MS = 5000L
        private const val MAX_RETRY_DELAY_MS = 60000L
        private const val DISCOVERY_WATCHDOG_TIMEOUT_MS = 8000L
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val isDiscovering = AtomicBoolean(false)
    private val isResolving = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = BASE_RETRY_DELAY_MS
    private var watchdogRunnable: Runnable? = null

    fun startDiscovery() {
        if (isDiscovering.getAndSet(true)) {
            Log.d(TAG, "[DISCOVERY] startDiscovery ignored: Discovery already active")
            return
        }

        Log.i(TAG, "[DISCOVERY] [1/12] Starting mDNS discovery for service type: $SERVICE_TYPE")
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            val deviceIp = if (ipInt != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else "Unknown/Interface"

            multicastLock = wifiManager?.createMulticastLock("WatchmenDiscoveryLock")?.apply {
                setReferenceCounted(false)
                acquire()
                Log.d(TAG, "[DISCOVERY] WifiManager.MulticastLock acquired for IP $deviceIp (referenceCounted=false)")
            }
            Log.i(TAG, "[DISCOVERY] Active device Wi-Fi IP: $deviceIp | MulticastLock held: ${multicastLock?.isHeld}")

            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                Log.w(TAG, "[DISCOVERY] NsdManager not available on this device")
                isDiscovering.set(false)
                BackendEndpointManager.notifyDiscoveryFailed()
                return
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "[DISCOVERY] DISCOVERY_START_FAILED: code $errorCode for serviceType=$serviceType")
                    stopDiscovery()
                    BackendEndpointManager.notifyDiscoveryFailed()
                    scheduleRetry()
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "[DISCOVERY] DISCOVERY_STOP_FAILED: code $errorCode for serviceType=$serviceType")
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.i(TAG, "[DISCOVERY] [2/12] Android NsdManager discovery started for $serviceType")
                    retryDelayMs = BASE_RETRY_DELAY_MS
                    scheduleWatchdog()
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    Log.i(TAG, "[DISCOVERY] Android NsdManager discovery listener stopped")
                    isDiscovering.set(false)
                    cancelWatchdog()
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    Log.i(TAG, "[DISCOVERY] [3/12] Android onServiceFound fired: name='${serviceInfo.serviceName}' | type='${serviceInfo.serviceType}'")

                    if (serviceInfo.serviceType.contains("_watchmen", ignoreCase = true)) {
                        Log.i(TAG, "[DISCOVERY] [4/12] Correct _watchmen._tcp service matched: '${serviceInfo.serviceName}'")
                        resolveServiceSafely(serviceInfo)
                    } else {
                        Log.d(TAG, "[DISCOVERY] Ignoring non-watchmen service type: ${serviceInfo.serviceType}")
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    Log.w(TAG, "[DISCOVERY] Service lost: '${serviceInfo?.serviceName}'")
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "[DISCOVERY] Failed to initiate mDNS discovery: ${e.message}", e)
            isDiscovering.set(false)
            BackendEndpointManager.notifyDiscoveryFailed()
            scheduleRetry()
        }
    }

    private fun resolveServiceSafely(serviceInfo: NsdServiceInfo, attemptCount: Int = 1) {
        if (!isResolving.compareAndSet(false, true)) {
            Log.d(TAG, "[DISCOVERY] Resolution currently active for another service, skipping concurrent resolve for '${serviceInfo.serviceName}'")
            return
        }

        Log.i(TAG, "[DISCOVERY] [5/12] Android resolveService fired for '${serviceInfo.serviceName}' (attempt $attemptCount)")
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "[DISCOVERY] Service resolve failed: code $errorCode for '${info?.serviceName}'")
                    isResolving.set(false)

                    // Retry resolution after 1 second if failed (e.g. code 3 FAILURE_ALREADY_ACTIVE)
                    if (attemptCount <= 3) {
                        handler.postDelayed({
                            if (isDiscovering.get() && !BackendEndpointManager.isConfigured()) {
                                Log.i(TAG, "[DISCOVERY] Retrying resolution for '${serviceInfo.serviceName}' (attempt ${attemptCount + 1})...")
                                resolveServiceSafely(serviceInfo, attemptCount + 1)
                            }
                        }, 1000L)
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo?) {
                    try {
                        cancelWatchdog()
                        val rawHost = info?.host?.hostAddress
                        val port = info?.port ?: 8000

                        Log.i(TAG, "[DISCOVERY] [6/12] Resolved host address obtained: rawHost='$rawHost'")
                        Log.i(TAG, "[DISCOVERY] [7/12] Resolved port obtained: port=$port")

                        val candidateHost = if (!rawHost.isNullOrBlank() && rawHost != "0.0.0.0" && rawHost != "127.0.0.1") {
                            rawHost.trimStart('/').substringBefore('%')
                        } else {
                            val attrHost = info?.attributes?.get("host")?.let { String(it) }
                            if (!attrHost.isNullOrBlank()) {
                                Log.i(TAG, "[DISCOVERY] Fallback to TXT record host attribute: '$attrHost'")
                                attrHost.trim()
                            } else null
                        }

                        if (!candidateHost.isNullOrBlank() && candidateHost != "0.0.0.0" && candidateHost != "127.0.0.1") {
                            val resolvedUrl = "http://$candidateHost:$port"
                            Log.i(TAG, "[DISCOVERY] [8/12] Calling BackendEndpointManager.updateEndpoint($resolvedUrl)")
                            BackendEndpointManager.updateEndpoint(resolvedUrl, context)
                        } else {
                            Log.w(TAG, "[DISCOVERY] Unable to resolve valid backend IP: host='$rawHost', candidate='$candidateHost'")
                        }
                    } finally {
                        isResolving.set(false)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "[DISCOVERY] Error invoking resolveService: ${e.message}", e)
            isResolving.set(false)
        }
    }

    private fun scheduleWatchdog() {
        cancelWatchdog()
        watchdogRunnable = Runnable {
            if (isDiscovering.get() && !BackendEndpointManager.isConfigured()) {
                Log.w(TAG, "[DISCOVERY] Watchdog timeout ($DISCOVERY_WATCHDOG_TIMEOUT_MS ms) reached without resolution.")
                BackendEndpointManager.notifyDiscoveryFailed()
            }
        }
        handler.postDelayed(watchdogRunnable!!, DISCOVERY_WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun scheduleRetry() {
        handler.postDelayed({
            Log.i(TAG, "[DISCOVERY] Retry discovery scheduled after ${retryDelayMs}ms")
            startDiscovery()
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }, retryDelayMs)
    }

    fun stopDiscovery() {
        cancelWatchdog()
        if (!isDiscovering.getAndSet(false)) return
        try {
            if (discoveryListener != null && nsdManager != null) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DISCOVERY] Error stopping discovery: ${e.message}")
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                    Log.d(TAG, "[DISCOVERY] Released WifiManager.MulticastLock")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[DISCOVERY] Error releasing MulticastLock: ${e.message}")
            }
            multicastLock = null
            discoveryListener = null
            isResolving.set(false)
        }
    }
}

