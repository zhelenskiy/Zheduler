package com.zhelenskiy.zheduler.zheduler.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {

    // Every hash here uses a deliberately small iteration count. The parameters are stored in the
    // hash, so this exercises the same code path as production without spending a fifth of a
    // second per assertion.
    private val fastIterations = 1_000

    @Test
    fun `a password verifies against its own hash`() {
        val stored = Passwords.hash("a perfectly ordinary passphrase", fastIterations)
        assertTrue(Passwords.verify("a perfectly ordinary passphrase", stored))
    }

    @Test
    fun `a different password does not verify`() {
        val stored = Passwords.hash("a perfectly ordinary passphrase", fastIterations)
        assertFalse(Passwords.verify("a perfectly ordinary passphras", stored))
        assertFalse(Passwords.verify("A perfectly ordinary passphrase", stored))
        assertFalse(Passwords.verify("", stored))
    }

    @Test
    fun `the same password hashes differently every time`() {
        val first = Passwords.hash("repeated password", fastIterations)
        val second = Passwords.hash("repeated password", fastIterations)
        assertNotEquals(first, second, "a per-account salt is what stops one rainbow table serving all of them")
        assertTrue(Passwords.verify("repeated password", first))
        assertTrue(Passwords.verify("repeated password", second))
    }

    @Test
    fun `the stored form carries its parameters`() {
        val stored = Passwords.hash("parameterised", fastIterations)
        val parts = stored.split('$')
        assertEquals(4, parts.size)
        assertEquals("pbkdf2-sha256", parts[0])
        assertEquals(fastIterations.toString(), parts[1])
    }

    @Test
    fun `a hash made with fewer iterations is marked for rehashing`() {
        assertTrue(Passwords.needsRehash(Passwords.hash("old", fastIterations)))
        assertFalse(Passwords.needsRehash(Passwords.hash("new", Passwords.DEFAULT_ITERATIONS)))
    }

    @Test
    fun `a stored value this build cannot read fails closed`() {
        // A row written by a newer server, or a corrupted one, must lock that account out rather
        // than throw an exception that takes the sign-in endpoint down for everyone.
        listOf(
            "",
            "not-a-hash",
            "argon2id\$3\$c2FsdA==\$aGFzaA==",
            "pbkdf2-sha256\$notanumber\$c2FsdA==\$aGFzaA==",
            "pbkdf2-sha256\$0\$c2FsdA==\$aGFzaA==",
            "pbkdf2-sha256\$1000\$not base64!\$aGFzaA==",
            "pbkdf2-sha256\$1000\$c2FsdA==",
        ).forEach { malformed ->
            assertFalse(Passwords.verify("anything", malformed), "should refuse \"$malformed\"")
            assertFalse(Passwords.needsRehash(malformed), "should not ask to rehash \"$malformed\"")
        }
    }

    @Test
    fun `a tampered hash does not verify`() {
        val stored = Passwords.hash("tamper target", fastIterations)
        val parts = stored.split('$')
        // Flip the first character of the encoded hash. Base64 alphabets being what they are,
        // "A" and "B" are both valid, so this stays decodable and only the value changes.
        val flipped = parts[3].let { if (it.startsWith("A")) "B" + it.drop(1) else "A" + it.drop(1) }
        val tampered = "${parts[0]}\$${parts[1]}\$${parts[2]}\$$flipped"
        assertFalse(Passwords.verify("tamper target", tampered))
    }

    @Test
    fun `the decoy always refuses`() {
        assertFalse(Passwords.verifyDecoy("anything at all"))
        assertFalse(Passwords.verifyDecoy(""))
    }
}
