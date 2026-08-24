package com.zhelenskiy.zheduler.zheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerAddressTest {

    private fun accepted(raw: String): ServerAddress {
        val outcome = ServerAddress.parse(raw)
        assertIs<Outcome.Success<ServerAddress>>(outcome, "refused \"$raw\": ${outcome.errorOrNull()?.message}")
        return outcome.value
    }

    private fun refused(raw: String): RemoteError {
        val outcome = ServerAddress.parse(raw)
        assertIs<Outcome.Failure>(outcome, "accepted \"$raw\"")
        return outcome.error
    }

    @Test
    fun `an https address is accepted and keeps its port`() {
        assertEquals("https://sync.example.com", accepted("https://sync.example.com").value)
        assertEquals("https://sync.example.com:8443", accepted("https://sync.example.com:8443").value)
        assertEquals("https://192.0.2.10:8443", accepted("https://192.0.2.10:8443").value)
        assertEquals("https://[2001:db8::1]:8443", accepted("https://[2001:db8::1]:8443").value)
    }

    @Test
    fun `surrounding space and trailing slashes are ignored`() {
        assertEquals("https://sync.example.com", accepted("  https://sync.example.com///  ").value)
    }

    @Test
    fun `the scheme is read case-insensitively`() {
        assertEquals("https://sync.example.com", accepted("HTTPS://sync.example.com").value)
    }

    @Test
    fun `the api path is appended once with no doubled slash`() {
        assertEquals("https://sync.example.com/api/v1", accepted("https://sync.example.com/").apiBase)
    }

    @Test
    fun `plain http is refused for anything that leaves the machine`() {
        val error = refused("http://sync.example.com")
        assertIs<RemoteError.InsecureAddress>(error)
        assertEquals(RemoteRemedy.ReviewSettings, error.remedy)
        assertTrue("https" in error.message, error.message)
    }

    @Test
    fun `plain http to a loopback host is allowed because nothing is on the wire`() {
        assertEquals("http://localhost:8080", accepted("http://localhost:8080").value)
        assertEquals("http://127.0.0.1:8080", accepted("http://127.0.0.1:8080").value)
        assertEquals("http://[::1]:8080", accepted("http://[::1]:8080").value)
        assertEquals("http://LocalHost:8080", accepted("http://LocalHost:8080").value)
    }

    @Test
    fun `a host that merely looks like loopback is still refused`() {
        // The check is on the host, not on the text: these all resolve elsewhere.
        listOf(
            "http://localhost.example.com",
            "http://notlocalhost",
            "http://127.0.0.1.example.com",
            "http://localhosts",
        ).forEach { raw -> assertIs<RemoteError.InsecureAddress>(refused(raw)) }
    }

    @Test
    fun `an address with a path query or fragment is refused rather than trimmed`() {
        // Silently dropping the path would send the password to a host the user did not name.
        listOf(
            "https://sync.example.com/api",
            "https://sync.example.com/api/v1",
            "https://sync.example.com?token=abc",
            "https://sync.example.com#anchor",
        ).forEach { raw -> assertIs<RemoteError.InsecureAddress>(refused(raw)) }
    }

    @Test
    fun `an address carrying credentials is refused`() {
        assertIs<RemoteError.InsecureAddress>(refused("https://user:password@sync.example.com"))
        assertIs<RemoteError.InsecureAddress>(refused("https://user@sync.example.com"))
    }

    @Test
    fun `an address with no scheme no host or a bad port is refused`() {
        listOf(
            "",
            "   ",
            "sync.example.com",
            "://sync.example.com",
            "ftp://sync.example.com",
            "javascript://sync.example.com",
            "https://",
            "https://:8443",
            "https://sync.example.com:0",
            "https://sync.example.com:70000",
            "https://sync.example.com:abc",
            "https://sync example.com",
            "https://[2001:db8::1",
            "https://2001:db8::1",
        ).forEach { raw -> assertIs<RemoteError.InsecureAddress>(refused(raw)) }
    }
}
