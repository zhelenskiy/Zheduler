package com.zhelenskiy.zheduler.zheduler.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokensTest {

    @Test
    fun `a minted token carries the full 256 bits`() {
        val token = Tokens.mint()
        // 32 bytes in unpadded base64url is 43 characters. Anything shorter would mean the
        // randomness was silently truncated somewhere.
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "must be URL-safe: $token")
    }

    @Test
    fun `tokens do not repeat`() {
        val minted = List(500) { Tokens.mint() }
        assertEquals(minted.size, minted.toSet().size)
    }

    @Test
    fun `a fingerprint is stable, is not the token, and differs per token`() {
        val token = Tokens.mint()
        assertContentEquals(Tokens.fingerprint(token), Tokens.fingerprint(token))
        assertEquals(32, Tokens.fingerprint(token).size)
        assertFalse(Tokens.fingerprint(token).contentEquals(Tokens.fingerprint(Tokens.mint())))
        assertFalse(
            Tokens.fingerprint(token).decodeToString().contains(token),
            "the stored form must not contain the token itself",
        )
    }

    @Test
    fun `the bearer scheme is read case-insensitively`() {
        assertEquals("abc", Tokens.fromAuthorizationHeader("Bearer abc"))
        assertEquals("abc", Tokens.fromAuthorizationHeader("bearer abc"))
        assertEquals("abc", Tokens.fromAuthorizationHeader("BEARER abc"))
        assertEquals("abc", Tokens.fromAuthorizationHeader("  Bearer   abc  "))
    }

    @Test
    fun `anything that is not a bearer token is not one`() {
        listOf(
            null,
            "",
            "   ",
            "abc",
            "Basic YWJjOmRlZg==",
            "Bearer",
            "Bearer ",
            "BearerToken abc",
            "Bea abc",
        ).forEach { header ->
            assertNull(Tokens.fromAuthorizationHeader(header), "should not read a token out of \"$header\"")
        }
    }
}
