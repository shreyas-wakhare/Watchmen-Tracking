package com.watchmen.tracker

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackendEndpointManagerTest {

    @Before
    fun setUp() {
        BackendEndpointManager.clearEndpoint()
    }

    @Test
    fun testUnconfiguredState() {
        assertFalse(BackendEndpointManager.isConfigured())
        assertNull(BackendEndpointManager.getHttpBaseUrl())
        assertNull(BackendEndpointManager.getWebSocketBaseUrl())
        assertNull(BackendEndpointManager.getHttpUrlOrNull("/telemetry"))
        assertNull(BackendEndpointManager.getWebSocketUrlOrNull("/ws"))

        try {
            BackendEndpointManager.getHttpUrl("/telemetry")
            fail("Expected IllegalStateException when unconfigured")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not yet configured") == true)
        }
    }

    @Test
    fun testNormalizeUrl_validHttp() {
        val normalized = BackendEndpointManager.normalizeUrl("http://watchmen-server:8000")
        assertEquals("http://watchmen-server:8000", normalized)
    }

    @Test
    fun testNormalizeUrl_missingScheme() {
        val normalized = BackendEndpointManager.normalizeUrl("watchmen-server:8000")
        assertEquals("http://watchmen-server:8000", normalized)
    }

    @Test
    fun testNormalizeUrl_wsScheme() {
        val normalized = BackendEndpointManager.normalizeUrl("ws://watchmen-server:8000")
        assertEquals("http://watchmen-server:8000", normalized)
    }

    @Test
    fun testNormalizeUrl_httpsScheme() {
        val normalized = BackendEndpointManager.normalizeUrl("https://watchmen.example.com")
        assertEquals("https://watchmen.example.com", normalized)
    }

    @Test
    fun testNormalizeUrl_wssScheme() {
        val normalized = BackendEndpointManager.normalizeUrl("wss://watchmen.example.com")
        assertEquals("https://watchmen.example.com", normalized)
    }

    @Test
    fun testNormalizeUrl_rejectsBindAddress() {
        assertNull(BackendEndpointManager.normalizeUrl("http://0.0.0.0:8000"))
        assertNull(BackendEndpointManager.normalizeUrl("0.0.0.0:8000"))
    }

    @Test
    fun testEndpointUpdate_httpAndWebSocketDerivation() {
        val updated = BackendEndpointManager.updateEndpoint("http://watchmen-server:8000")
        assertTrue(updated)
        assertTrue(BackendEndpointManager.isConfigured())
        assertEquals("http://watchmen-server:8000", BackendEndpointManager.getHttpBaseUrl())
        assertEquals("ws://watchmen-server:8000", BackendEndpointManager.getWebSocketBaseUrl())
    }

    @Test
    fun testEndpointUpdate_httpsAndWssDerivation() {
        BackendEndpointManager.updateEndpoint("https://backend.watchmen.com")
        assertEquals("https://backend.watchmen.com", BackendEndpointManager.getHttpBaseUrl())
        assertEquals("wss://backend.watchmen.com", BackendEndpointManager.getWebSocketBaseUrl())
    }

    @Test
    fun testSafeUrlConstruction_httpAndQueryParameters() {
        BackendEndpointManager.updateEndpoint("http://watchmen-server:8000/")
        
        val httpUrl = BackendEndpointManager.getHttpUrl(
            "/geofence/config",
            mapOf("device_id" to "DEVICE_123", "status" to "active")
        )
        
        assertEquals("http://watchmen-server:8000/geofence/config?device_id=DEVICE_123&status=active", httpUrl)
    }

    @Test
    fun testSafeUrlConstruction_webSocketAndQueryParameters() {
        BackendEndpointManager.updateEndpoint("http://watchmen-server:8000")
        
        val wsUrl = BackendEndpointManager.getWebSocketUrl(
            "/ws",
            mapOf("type" to "device", "deviceid" to "DEV 456")
        )
        
        assertEquals("ws://watchmen-server:8000/ws?type=device&deviceid=DEV%20456", wsUrl)
    }

    @Test
    fun testEndpointChangeListener() {
        var notifiedHttp = ""
        var notifiedWs = ""

        val listener = object : BackendEndpointManager.EndpointChangeListener {
            override fun onEndpointChanged(newHttpUrl: String, newWsUrl: String) {
                notifiedHttp = newHttpUrl
                notifiedWs = newWsUrl
            }
        }

        BackendEndpointManager.addListener(listener)
        BackendEndpointManager.updateEndpoint("http://watchmen-backend:9000")

        assertEquals("http://watchmen-backend:9000", notifiedHttp)
        assertEquals("ws://watchmen-backend:9000", notifiedWs)

        BackendEndpointManager.removeListener(listener)
    }
}
