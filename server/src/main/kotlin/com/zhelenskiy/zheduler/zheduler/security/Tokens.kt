package com.zhelenskiy.zheduler.zheduler.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Bearer tokens: 256 random bits, handed out once and never stored.
 *
 * What the database holds is the SHA-256 of the token, so a copy of the table is not a set of
 * working credentials. No salt and no stretching, deliberately: the input is already 256 bits of
 * randomness, so there is no dictionary to search and nothing for iteration count to buy.
 */
object Tokens {

    private const val TOKEN_BYTES = 32

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh token in the form the client will send back. URL-safe, so it survives any transport. */
    fun mint(): String = encoder.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    /** The lookup key for a token. The only form that is ever written down. */
    fun fingerprint(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    /**
     * The token out of an `Authorization` header, or null if there is not one.
     *
     * The scheme is matched case-insensitively because RFC 7235 says it is case-insensitive, and
     * a client that sends "bearer" is not wrong.
     */
    fun fromAuthorizationHeader(header: String?): String? {
        val value = header?.trim() ?: return null
        // Compared against the scheme's own length, not against wherever the first space fell:
        // matching only as far as the space accepted "Bea abc" as a bearer token.
        if (value.length <= SCHEME.length) return null
        if (!value.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
        if (!value[SCHEME.length].isWhitespace()) return null
        return value.substring(SCHEME.length + 1).trim().takeIf { it.isNotEmpty() }
    }

    private const val SCHEME = "Bearer"
}
