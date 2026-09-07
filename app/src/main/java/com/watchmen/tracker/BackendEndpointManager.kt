package com.watchmen.tracker

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single Source of Truth for backend server URL resolution and dynamic configuration.
 *
 * Automatically manages:
 * - Deterministic resolution: mDNS discovery -> Cached last-known endpoint -> Unconfigured state
 * - Strict zero-fallback: Absolutely NO machine-specific developer LAN IP (192.168.x.x) is hardcoded
 * - Dynamic derivation: WebSocket Base URL is strictly derived from HTTP Base URL (http->ws, https->wss)
 * - Safe URL construction: Handles trailing/leading slashes, segments, and parameter encoding
 * - Thread-safe, coroutine-friendly listener notifications when endpoint changes
 */
object BackendEndpointManager {

    private const val TAG = "BackendEndpointManager"
    private const val PREF_NAME = "watchmen_prefs"
    private const val KEY_SERVER_URL = "server_url"

    @Volatile
    private var activeHttpBaseUrl: String? = null

    @Volatile
    private var activeWebSocketBaseUrl: String? = null

    private val listeners = CopyOnWriteArrayList<EndpointChangeListener>()
    private var isInitialized = false

    interface EndpointChangeListener {
        fun onEndpointChanged(newHttpUrl: String, newWsUrl: String)
    }

    /**
     * Initialize endpoint manager from SharedPreferences cached endpoint if previously discovered/configured.
     */
    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)

        if (!savedUrl.isNullOrBlank()) {
            val normalized = normalizeUrl(savedUrl)
            if (normalized != null) {
                applyEndpointInternal(normalized, notifyListeners = false)
            }
        }

        isInitialized = true
        logI(TAG, "WS DEBUG: BackendEndpointManager.init | Configured: ${isConfigured()} | HTTP: $activeHttpBaseUrl | WS: $activeWebSocketBaseUrl")
    }

    /**
     * Check whether a valid backend endpoint is currently configured/resolved.
     */
    fun isConfigured(): Boolean = !activeHttpBaseUrl.isNullOrBlank()

    /**
     * Update active server URL at runtime (e.g. from mDNS discovery or UI settings).
     * Returns true if URL was valid and applied.
     */
    fun updateEndpoint(rawUrl: String, context: Context? = null): Boolean {
        logI(TAG, "WS DEBUG: updateEndpoint called with rawUrl: $rawUrl")
        val normalized = normalizeUrl(rawUrl) ?: return false
        val changed = applyEndpointInternal(normalized, notifyListeners = true)
        logI(TAG, "WS DEBUG: updateEndpoint applied | changed: $changed | HTTP: $activeHttpBaseUrl | WS: $activeWebSocketBaseUrl")

        if (context != null && changed && activeHttpBaseUrl != null) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SERVER_URL, activeHttpBaseUrl).apply()
            logI(TAG, "Saved new backend URL to preferences: $activeHttpBaseUrl")
        }

        return true
    }

    /**
     * Clear active endpoint (e.g. when explicit reset is required).
     */
    fun clearEndpoint(context: Context? = null) {
        activeHttpBaseUrl = null
        activeWebSocketBaseUrl = null
        if (context != null) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SERVER_URL).apply()
        }
        logI(TAG, "Cleared backend endpoint")
    }

    /**
     * Get the active HTTP base URL (e.g. "http://192.168.1.100:8000" or null if not yet resolved)
     */
    fun getHttpBaseUrl(): String? = activeHttpBaseUrl

    /**
     * Get the active WebSocket base URL (e.g. "ws://192.168.1.100:8000" or null if not yet resolved)
     */
    fun getWebSocketBaseUrl(): String? = activeWebSocketBaseUrl

    /**
     * Safely construct full HTTP URL for an endpoint path, or null if backend is not yet resolved.
     */
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

    /**
     * Safely construct full WebSocket URL for an endpoint path, or null if backend is not yet resolved.
     */
    fun getWebSocketUrlOrNull(path: String, queryParams: Map<String, String>? = null): String? {
        val httpUrlStr = getHttpUrlOrNull(path, queryParams)
        val wsUrl = if (httpUrlStr != null) convertToWebSocketUrl(httpUrlStr) else null
        logI(TAG, "WS DEBUG: getWebSocketUrlOrNull | path: $path | result: $wsUrl")
        return wsUrl
    }

    /**
     * Safely construct full HTTP URL for an endpoint path.
     * Throws IllegalStateException if backend has not been discovered or configured yet.
     */
    fun getHttpUrl(path: String, queryParams: Map<String, String>? = null): String {
        return getHttpUrlOrNull(path, queryParams)
            ?: throw IllegalStateException("Backend endpoint not yet configured or discovered")
    }

    /**
     * Safely construct full WebSocket URL for an endpoint path.
     * Throws IllegalStateException if backend has not been discovered or configured yet.
     */
    fun getWebSocketUrl(path: String, queryParams: Map<String, String>? = null): String {
        return getWebSocketUrlOrNull(path, queryParams)
            ?: throw IllegalStateException("Backend endpoint not yet configured or discovered")
    }

    /**
     * Register a listener for runtime endpoint changes
     */
    fun addListener(listener: EndpointChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Unregister an endpoint change listener
     */
    fun removeListener(listener: EndpointChangeListener) {
        listeners.remove(listener)
    }

    private fun applyEndpointInternal(normalizedUrl: String, notifyListeners: Boolean): Boolean {
        val newHttp = normalizedUrl.trimEnd('/')
        val newWs = convertToWebSocketUrl(newHttp)

        val hasChanged = newHttp != activeHttpBaseUrl

        activeHttpBaseUrl = newHttp
        activeWebSocketBaseUrl = newWs

        if (hasChanged && notifyListeners) {
            logI(TAG, "WS DEBUG: Backend endpoint changed! HTTP: $newHttp | WS: $newWs | notifying ${listeners.size} listeners")
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

    /**
     * Normalizes user-input or discovered URL to standard http/https format.
     */
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

        // Do not accept bind address 0.0.0.0 as client destination
        if (host == "0.0.0.0") return null

        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
    }

    /**
     * Converts http:// -> ws:// and https:// -> wss://
     */
    private fun convertToWebSocketUrl(httpUrl: String): String {
        return when {
            httpUrl.startsWith("https://", ignoreCase = true) -> "wss://" + httpUrl.substring(8)
            httpUrl.startsWith("http://", ignoreCase = true) -> "ws://" + httpUrl.substring(7)
            else -> "ws://$httpUrl"
        }
    }

    private fun logI(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (e: Throwable) {
            println("[$tag] INFO: $msg")
        }
    }

    private fun logE(tag: String, msg: String, t: Throwable? = null) {
        try {
            Log.e(tag, msg, t)
        } catch (e: Throwable) {
            println("[$tag] ERROR: $msg ${t?.message ?: ""}")
        }
    }
}
