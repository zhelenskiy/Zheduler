package com.zhelenskiy.zheduler.zheduler.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * How passwords are stored: PBKDF2-HMAC-SHA256, salted per account.
 *
 * PBKDF2 rather than Argon2 or scrypt because it is in the JDK, and a hashing scheme that arrives
 * with no third-party code is one fewer thing between a password and the disk. The cost is that
 * PBKDF2 is cheap on a GPU relative to the memory-hard alternatives, which is what the high
 * iteration count is buying back.
 *
 * The stored form carries its own parameters — `pbkdf2-sha256$iterations$salt$hash` — so raising
 * [DEFAULT_ITERATIONS] later keeps every existing password verifiable, and [needsRehash] says
 * which ones to upgrade the next time their owner signs in.
 */
object Passwords {

    /** OWASP's floor for PBKDF2-HMAC-SHA256 as of 2023. */
    const val DEFAULT_ITERATIONS: Int = 210_000

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SCHEME = "pbkdf2-sha256"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /**
     * A hash of a password that has never been stored, used to spend the same time on a sign-in
     * for an account that does not exist as on one that does.
     *
     * Without it, "no such user" answers in microseconds and "wrong password" in a fifth of a
     * second, which tells anyone who is asking exactly which usernames are real.
     */
    private val decoyHash: String by lazy { hash("decoy password, never a real one") }

    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        return "$SCHEME\$$iterations\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(derived)}"
    }

    /**
     * Whether [password] is the one [stored] was made from.
     *
     * A stored value this code cannot read is a false, not an exception: a row corrupted or
     * written by a newer server must lock that one account out, not take the endpoint down.
     */
    fun verify(password: String, stored: String): Boolean {
        val parsed = parse(stored) ?: return false
        val derived = derive(password, parsed.salt, parsed.iterations)
        return MessageDigest.isEqual(derived, parsed.hash)
    }

    /**
     * Spends a verification's worth of time and returns false.
     *
     * Called where there is no stored hash to check against, so that the answer takes as long as
     * a real rejection. See [decoyHash].
     */
    fun verifyDecoy(password: String): Boolean = verify(password, decoyHash)

    /** Whether [stored] was made with weaker parameters than this build now uses. */
    fun needsRehash(stored: String): Boolean {
        val parsed = parse(stored) ?: return false
        return parsed.iterations < DEFAULT_ITERATIONS
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(stored: String): Parsed? {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != SCHEME) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return try {
            Parsed(iterations, decoder.decode(parts[2]), decoder.decode(parts[3]))
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // Clears the copy PBEKeySpec made; the caller's String is beyond reach either way.
            spec.clearPassword()
        }
    }
}
