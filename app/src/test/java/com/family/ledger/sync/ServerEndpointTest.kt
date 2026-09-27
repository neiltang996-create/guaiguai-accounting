package com.family.ledger.sync

import org.junit.Assert.*
import org.junit.Test

/** The public build has no real endpoints or secrets; overrides retain deterministic order. */
class ServerEndpointTest {
    @Test fun publicDefaultsContainNoEndpointOrToken() {
        assertEquals("", ServerEndpoint.DEFAULT_LAN_URL)
        assertEquals("", ServerEndpoint.DEFAULT_WAN_URL)
        assertEquals("", ServerEndpoint.DEFAULT_TOKEN)
        assertTrue(ServerEndpoint.BUILT_IN.isEmpty())
        assertTrue(ServerEndpoint.candidates().isEmpty())
    }
    @Test fun overrideBeforeCachedEndpointAndDuplicatesRemoved() {
        assertEquals(listOf("https://sync.example.invalid", "https://cache.example.invalid"),
            ServerEndpoint.candidates(" https://sync.example.invalid/ ", "https://cache.example.invalid/"))
        assertEquals(listOf("https://sync.example.invalid"),
            ServerEndpoint.candidates("https://sync.example.invalid", "https://sync.example.invalid/"))
    }
    @Test fun tokenOverrideIsTrimmedAndBlankRemainsBlank() {
        assertEquals("", ServerEndpoint.token("   "))
        assertEquals("example-test-token", ServerEndpoint.token(" example-test-token "))
    }
    @Test fun privateAddressesAndInvalidInput() {
        assertTrue(ServerEndpoint.isPrivate("http://10.0.0.5"))
        assertTrue(ServerEndpoint.isPrivate("http://172.20.3.4"))
        assertTrue(ServerEndpoint.isPrivate("http://192.168.0.1"))
        assertTrue(ServerEndpoint.isPrivate("http://localhost"))
        assertFalse(ServerEndpoint.isPrivate("https://sync.example.invalid"))
        assertFalse(ServerEndpoint.isPrivate("not-an-address"))
        assertFalse(ServerEndpoint.isPrivate(""))
    }
}
