package com.zhelenskiy.zheduler.zheduler.sync

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * A server address that has already been checked, so that nothing downstream has to wonder.
 *
 * The whole point is the plaintext rule: this app sends a password and then a bearer token to
 * whatever the user typed, and `http://` would put both on the wire in the clear for anyone on the
 * path. Only a loopback host is exempt, because there is no path — the packets never leave the
 * machine — and that is what makes running the server locally possible without a certificate.
 */
@Serializable
@JvmInline
value class ServerAddress private constructor(val value: String) {

    /** The base URL of the API, with no trailing slash: `https://host:port/api/v1`. */
    val apiBase: String get() = value + SyncProtocol.BASE_PATH

    override fun toString(): String = value

    companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

        /**
         * Reads what the user typed, or explains what is wrong with it.
         *
         * Deliberately strict. A URL that carries a path, a query, credentials or a fragment is
         * rejected rather than silently trimmed, because each of those is a sign the user pasted
         * something other than a server root, and quietly dropping it would send their password
         * somewhere they did not name.
         */
        fun parse(raw: String): Outcome<ServerAddress> {
            val input = raw.trim()
            if (input.isEmpty()) return refuse("Enter the address of your server.")

            val schemeEnd = input.indexOf("://")
            if (schemeEnd <= 0) {
                return refuse("Start the address with https:// so your password is encrypted.")
            }
            val scheme = input.substring(0, schemeEnd).lowercase()
            if (scheme != "https" && scheme != "http") {
                return refuse("\"$scheme\" is not an address this app can use; use https://.")
            }

            // Only trailing slashes are dropped, and only here: "https://host/api" keeps its path
            // and is refused below rather than being turned into "https://host".
            val authority = input.substring(schemeEnd + 3).trimEnd('/')
            if (authority.isEmpty()) return refuse("The address is missing a host name.")
            if ('/' in authority) {
                return refuse("Enter only the server's address, with no path after it.")
            }
            if ('?' in authority || '#' in authority) {
                return refuse("Enter only the server's address, with no query or fragment.")
            }
            if ('@' in authority) {
                return refuse("Enter the address without a username in it; sign in below instead.")
            }
            if (authority.any { it.isWhitespace() }) return refuse("The address may not contain spaces.")

            val split = splitAuthority(authority) ?: return refuse("The address is missing a host name.")
            val (host, portText) = split
            if (host.isEmpty()) return refuse("The address is missing a host name.")
            if (portText != null) {
                val port = portText.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    return refuse("\"$portText\" is not a usable port number.")
                }
            }

            val isLoopback = host.lowercase() in LOOPBACK_HOSTS
            if (scheme == "http" && !isLoopback) {
                return refuse(
                    "http:// would send your password unencrypted. Use https://, " +
                        "or run the server on this device."
                )
            }

            return Outcome.Success(ServerAddress("$scheme://$authority"))
        }

        private fun refuse(reason: String): Outcome<ServerAddress> =
            Outcome.Failure(RemoteError.InsecureAddress(reason))

        /**
         * Host and port text, keeping a bracketed IPv6 literal whole.
         *
         * Null means the authority is not one this app can read at all — an unclosed bracket, or
         * a colon with nothing usable around it.
         */
        private fun splitAuthority(authority: String): Pair<String, String?>? {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val host = authority.substring(0, close + 1)
                if (host.length <= 2) return null
                val rest = authority.substring(close + 1)
                return when {
                    rest.isEmpty() -> host to null
                    rest.startsWith(":") -> host to rest.substring(1)
                    else -> null
                }
            }
            val colon = authority.indexOf(':')
            if (colon < 0) return authority to null
            // A second colon outside brackets is an IPv6 literal that was written without them.
            if (authority.indexOf(':', colon + 1) >= 0) return null
            return authority.substring(0, colon) to authority.substring(colon + 1)
        }
    }
}
