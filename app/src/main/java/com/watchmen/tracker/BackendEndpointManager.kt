package com.watchmen.tracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single Source of Truth for backend server URL resolution and dynamic configuration.
 *
 * Automatically manages:
 * - Centralized mDNS discovery ownership via embedded BackendDiscoveryManager
 * - Discovery state representation (UNCONFIGURED, DISCOVERING, CONNECTED, DISCOVERY_FAILED)
 * - Deterministic resolution: mDNS discovery -> Cached last-known endpoint -> Unconfigured state
 * - Strict zero-fallback: Absolutely NO machine-specific developer LAN IP is hardcoded
 * - Dynamic derivation: WebSocket Base URL is strictly derived from HTTP Base URL (http->ws, https->wss)
 * - Safe URL construction: Handles trailing/leading slashes, segments, and parameter encoding
 * - Thread-safe, coroutine-friendly listener notifications when endpoint changes
 */
object BackendEndpointManager {

    private const val TAG = "BackendEndpointManager"
    private const val PREF_NAME = "watchmen_prefs"
    private const val KEY_SERVER_URL = "server_url"

    enum class DiscoveryState {
        UNCONFIGURED,
        DISCOVERING,
        CONNECTED,
        DISCOVERY_FAILED
    }

    @Volatile
    private var activeHttpBaseUrl: String? = null

    @Volatile
    private var activeWebSocketBaseUrl: String? = null

    @Volatile
    private var currentDiscoveryState: DiscoveryState = DiscoveryState.UNCONFIGURED

    private var globalDiscoveryManager: BackendDiscoveryManager? = null
    private val listeners = CopyOnWriteArrayList<EndpointChangeListener>()
    private var isInitialized = false

    interface EndpointChangeListener {
        fun onEndpointChanged(newHttpUrl: String, newWsUrl: String) {}
        fun onDiscoveryStateChanged(state: DiscoveryState, currentUrl: String?) {}
    }

    /**
     * Initialize endpoint manager and start global mDNS discovery on application launch.
     */
    @Synchronized
    fun init(context: Context) {
        val appContext = context.applicationContext
        if (!isInitialized) {
            val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(KEY_SERVER_URL, null)

            if (!savedUrl.isNullOrBlank()) {
                val normalized = normalizeUrl(savedUrl)
                if (normalized != null) {
                    applyEndpointInternal(normalized, notifyListeners = false)
                    setDiscoveryState(DiscoveryState.CONNECTED)
                }
            }

            isInitialized = true
            logI(TAG, "BackendEndpointManager initialized. Configured: ${isConfigured()} | HTTP: $activeHttpBaseUrl | State: $currentDiscoveryState")
        }

        // Always ensure global mDNS discovery is active
        startGlobalDiscovery(appContext)
    }

    @Synchronized
    fun startGlobalDiscovery(context: Context) {
        val appContext = context.applicationContext
        if (globalDiscoveryManager == null) {
            globalDiscoveryManager = BackendDiscoveryManager(appContext)
        }
        if (!isConfigured()) {
            setDiscoveryState(DiscoveryState.DISCOVERING)
        }
        globalDiscoveryManager?.startDiscovery()
    }

    @Synchronized
    fun stopGlobalDiscovery() {
        globalDiscoveryManager?.stopDiscovery()
    }

    /**
     * Check whether a valid backend endpoint is currently configured/resolved.
     */
    fun isConfigured(): Boolean = !activeHttpBaseUrl.isNullOrBlank()

    fun getDiscoveryState(): DiscoveryState = currentDiscoveryState

    /**
     * Awaits endpoint resolution if discovery is currently active.
     * Returns resolved HTTP base URL, or null if timeout occurs.
     */
    suspend fun awaitEndpoint(timeoutMs: Long = 3000L): String? {
        if (isConfigured()) {
            return activeHttpBaseUrl
        }

        return withTimeoutOrNull(timeoutMs) {
            while (!isConfigured() && currentDiscoveryState == DiscoveryState.DISCOVERING) {
                delay(100L)
            }
            activeHttpBaseUrl
        }
    }

    /**
     * Update active server URL at runtime (from mDNS discovery or manual configuration).
     */
    fun updateEndpoint(rawUrl: String, context: Context? = null): Boolean {
        logI(TAG, "updateEndpoint called with rawUrl: $rawUrl")
        val normalized = normalizeUrl(rawUrl) ?: return false
        val changed = applyEndpointInternal(normalized, notifyListeners = true)
        setDiscoveryState(DiscoveryState.CONNECTED)

        if (context != null && activeHttpBaseUrl != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SERVER_URL, activeHttpBaseUrl).apply()
            logI(TAG, "Saved new backend URL to preferences: $activeHttpBaseUrl")
        }

        return true
    }

    fun notifyDiscoveryFailed() {
        if (!isConfigured()) {
            setDiscoveryState(DiscoveryState.DISCOVERY_FAILED)
        }
    }

    private fun setDiscoveryState(newState: DiscoveryState) {
        currentDiscoveryState = newState
        logI(TAG, "[BackendEndpoint] Discovery state -> $newState (URL: $activeHttpBaseUrl)")
        for (listener in listeners) {
            try {
                listener.onDiscoveryStateChanged(newState, activeHttpBaseUrl)
            } catch (e: Exception) {
                logE(TAG, "Error notifying discovery state listener: ${e.message}", e)
            }
        }
    }

    /**
     * Clear active endpoint (e.g. when explicit reset is required).
     */
    fun clearEndpoint(context: Context? = null) {
        activeHttpBaseUrl = null
        activeWebSocketBaseUrl = null
        setDiscoveryState(DiscoveryState.UNCONFIGURED)

        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SERVER_URL).apply()
        }
        logI(TAG, "Cleared backend endpoint")
    }

    fun getHttpBaseUrl(): String? = activeHttpBaseUrl

    fun getWebSocketBaseUrl(): String? = activeWebSocketBaseUrl

    fun getHttpUrlOrNull(path: String, queryParams: Map<String, String>? = null): String? {
        val currentBase = activeHttpBaseUrl ?: return null
        val baseHttpUrl = currentBase.toHttpUrlOrNull() ?: return null

        val builder = baseHttpUrl.newBuilder()
        val cleanPath = path.trimStart('/')
        if (cleanPath.isNotEmpty()) {
            for (segment in cleanPath.split("/")) {
                if (segment.isNotEmpty()) {
                    builder.addPathSegment(segment)
                }
            }
        }

        queryParams?.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }

        return builder.build().toString()
    }

    fun getWebSocketUrlOrNull(path: String, queryParams: Map<String, String>? = null): String? {
        val httpUrlStr = getHttpUrlOrNull(path, queryParams)
        return if (httpUrlStr != null) convertToWebSocketUrl(httpUrlStr) else null
    }

    fun getHttpUrl(path: String, queryParams: Map<String, String>? = null): String {
        return getHttpUrlOrNull(path, queryParams)
            ?: throw IllegalStateException("Backend endpoint not yet configured or discovered")
    }

    fun getWebSocketUrl(path: String, queryParams: Map<String, String>? = null): String {
        return getWebSocketUrlOrNull(path, queryParams)
            ?: throw IllegalStateException("Backend endpoint not yet configured or discovered")
    }

    fun addListener(listener: EndpointChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // Immediately dispatch initial state
            listener.onDiscoveryStateChanged(currentDiscoveryState, activeHttpBaseUrl)
        }
    }

    fun removeListener(listener: EndpointChangeListener) {
        listeners.remove(listener)
    }

    private fun applyEndpointInternal(normalizedUrl: String, notifyListeners: Boolean): Boolean {
        val newHttp = normalizedUrl.trimEnd('/')
        val newWs = convertToWebSocketUrl(newHttp)
        val hasChanged = newHttp != activeHttpBaseUrl

        activeHttpBaseUrl = newHttp
        activeWebSocketBaseUrl = newWs

        if (notifyListeners) {
            logI(TAG, "[BackendEndpoint] Endpoint update: HTTP: $newHttp | WS: $newWs | Notifying ${listeners.size} listeners")
            for (listener in listeners) {
                try {
                    listener.onEndpointChanged(newHttp, newWs)
                } catch (e: Exception) {
                    logE(TAG, "Error notifying listener: ${e.message}", e)
                }
            }
        }

        return hasChanged
    }

    fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            else -> "http://$trimmed"
        }

        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        val scheme = parsed.scheme
        val host = parsed.host
        val port = parsed.port

        if (host == "0.0.0.0") return null

        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
    }

    private fun convertToWebSocketUrl(httpUrl: String): String {
        return when {
            httpUrl.startsWith("https://", ignoreCase = true) -> "wss://" + httpUrl.substring(8)
            httpUrl.startsWith("http://", ignoreCase = true) -> "ws://" + httpUrl.substring(7)
            else -> "ws://$httpUrl"
        }
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (e: Throwable) { println("[$tag] INFO: $msg") }
    }

    private fun logE(tag: String, msg: String, t: Throwable? = null) {
        try { Log.e(tag, msg, t) } catch (e: Throwable) { println("[$tag] ERROR: $msg ${t?.message ?: ""}") }
    }
}
