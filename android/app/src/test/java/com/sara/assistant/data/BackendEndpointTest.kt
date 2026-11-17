package com.sara.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the URL derivation that the whole backend connection depends on.
 *
 * Runs on the JVM (`:app:testDebugUnitTest`), so it verifies the logic without a device.
 */
class BackendEndpointTest {

    private fun base(raw: String): String =
        BackendEndpoint.parse(raw).getOrThrow().httpBase

    private fun ws(raw: String): String =
        BackendEndpoint.parse(raw).getOrThrow().wsUrl

    @Test
    fun `http base derives ws url and health url`() {
        val endpoint = BackendEndpoint.parse("http://192.168.1.5:8000").getOrThrow()
        assertEquals("http://192.168.1.5:8000", endpoint.httpBase)
        assertEquals("ws://192.168.1.5:8000/ws/session", endpoint.wsUrl)
        assertEquals("http://192.168.1.5:8000/health", endpoint.healthUrl)
    }

    @Test
    fun `https base derives wss url`() {
        assertEquals("wss://sara.example.com/ws/session", ws("https://sara.example.com"))
    }

    @Test
    fun `ws input is normalised to http`() {
        assertEquals("http://127.0.0.1:8000", base("ws://127.0.0.1:8000"))
        assertEquals("ws://127.0.0.1:8000/ws/session", ws("ws://127.0.0.1:8000"))
    }

    @Test
    fun `wss input is normalised to https`() {
        assertEquals("https://tunnel.example.com", base("wss://tunnel.example.com"))
        assertEquals("wss://tunnel.example.com/ws/session", ws("wss://tunnel.example.com"))
    }

    @Test
    fun `copied websocket path is stripped`() {
        assertEquals("http://10.0.2.2:8000", base("ws://10.0.2.2:8000/ws/session"))
    }

    @Test
    fun `copied health url is stripped`() {
        assertEquals("http://127.0.0.1:8000", base("http://127.0.0.1:8000/health"))
    }

    @Test
    fun `bare host assumes http`() {
        assertEquals("http://192.168.0.10:8000", base("192.168.0.10:8000"))
        assertEquals("ws://192.168.0.10:8000/ws/session", ws("192.168.0.10:8000"))
    }

    @Test
    fun `trailing slash does not double up`() {
        assertEquals("http://127.0.0.1:8000", base("http://127.0.0.1:8000/"))
        assertEquals("http://127.0.0.1:8000/health", BackendEndpoint.parse("http://127.0.0.1:8000/")
            .getOrThrow().healthUrl)
    }

    @Test
    fun `path prefix is preserved`() {
        assertEquals("https://example.com/sara", base("https://example.com/sara/"))
        assertEquals(
            "wss://example.com/sara/ws/session",
            ws("https://example.com/sara"),
        )
    }

    @Test
    fun `ipv6 host keeps brackets`() {
        assertEquals("http://[::1]:8000", base("http://[::1]:8000"))
    }

    @Test
    fun `rejects unsupported scheme and empty input`() {
        assertTrue(BackendEndpoint.parse("ftp://host").isFailure)
        assertTrue(BackendEndpoint.parse("").isFailure)
        assertTrue(BackendEndpoint.parse("   ").isFailure)
    }

    @Test
    fun `parseOrDefault falls back instead of throwing`() {
        assertEquals(BackendEndpoint.DEFAULT, BackendEndpoint.parseOrDefault("nonsense://").httpBase)
        assertEquals(BackendEndpoint.DEFAULT, "http://127.0.0.1:8000")
    }
}