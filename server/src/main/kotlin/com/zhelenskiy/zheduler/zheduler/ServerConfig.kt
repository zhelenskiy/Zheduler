package com.zhelenskiy.zheduler.zheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Where the server keeps what it is given. */
sealed interface StorageConfig {

    /** A PostgreSQL database. The only setting that survives a restart. */
    data class Postgres(
        val jdbcUrl: String,
        val username: String,
        val password: String,
        val maxPoolSize: Int,
    ) : StorageConfig

    /**
     * Nothing at all: everything is lost when the process ends.
     *
     * Has to be asked for by name. A server that silently ran without persistence would look like
     * it was working right up until the first restart took every user's spaces with it.
     */
    data object InMemory : StorageConfig
}

/**
 * Everything the server reads from its environment, resolved once at startup.
 *
 * Defaults are the cautious ones throughout: no forwarded-header trust, no cross-origin access,
 * and a refusal to start at all rather than a guess when storage is not configured.
 */
data class ServerConfig(
    val port: Int,
    val host: String,
    val storage: StorageConfig,
    val tokenLifetime: Duration,
    /** Origins allowed to call the API from a browser. Empty means no browser may. */
    val allowedOrigins: List<String>,
    /**
     * Whether `X-Forwarded-For` may be believed.
     *
     * Off by default because the header is trivially forged, and the rate limiter keys on the
     * client address: trusting it on a directly-exposed server lets one caller pretend to be
     * thousands and defeats the limiter entirely.
     */
    val trustForwardedHeaders: Boolean,
    /** Whether to advertise HSTS. Only meaningful behind TLS, hence off by default. */
    val strictTransportSecurity: Boolean,
) {
    companion object {
        const val DEFAULT_PORT: Int = 8080

        /** How long the rate limiter remembers one caller's attempts. */
        val AUTH_RATE_WINDOW: Duration = 15.minutes

        /**
         * Failed sign-ins allowed per window against one account name.
         *
         * Low, because this is the number that has to stop a password being guessed, and nobody
         * mistypes their own password a dozen times in a quarter of an hour.
         */
        const val AUTH_ATTEMPTS_PER_WINDOW: Int = 12

        /**
         * Failed sign-ins allowed per window from one address, across every account.
         *
         * Higher than the per-account limit and never cleared by a success. That combination is
         * what stops one host spraying a password across a list of usernames: each account's own
         * counter only ever reaches one, so this is the only counter that sees the pattern, and a
         * counter a single successful sign-in could reset would be no counter at all. Set well
         * above what an office behind one address would produce by fumbling passwords.
         */
        const val AUTH_ATTEMPTS_PER_ADDRESS_PER_WINDOW: Int = 60

        /**
         * Accounts one address may create per window, counting successes.
         *
         * The only auth path where success has to be counted: creating an account is free to the
         * caller and costs the server a deliberate fifth of a second of PBKDF2, so an unlimited
         * one is a way to spend every core the server has from a single host.
         */
        const val SIGN_UPS_PER_ADDRESS_PER_WINDOW: Int = 5

        /**
         * Reads the environment, or explains what is missing.
         *
         * Returns the complaint rather than throwing so that `main` can print one line and exit
         * instead of a stack trace whose first useful word is on line nine.
         */
        fun fromEnvironment(env: (String) -> String?): Result<ServerConfig> = runCatching {
            val storage = when (val kind = env("ZHEDULER_STORAGE")?.lowercase()) {
                null, "postgres" -> {
                    val url = env("ZHEDULER_DB_URL")
                        ?: error(
                            "Set ZHEDULER_DB_URL to a PostgreSQL JDBC URL, or ZHEDULER_STORAGE=memory " +
                                "to run without persistence."
                        )
                    require(url.startsWith("jdbc:postgresql:")) {
                        "ZHEDULER_DB_URL must be a jdbc:postgresql: URL."
                    }
                    StorageConfig.Postgres(
                        jdbcUrl = url,
                        username = env("ZHEDULER_DB_USER") ?: error("Set ZHEDULER_DB_USER."),
                        password = env("ZHEDULER_DB_PASSWORD") ?: error("Set ZHEDULER_DB_PASSWORD."),
                        maxPoolSize = env("ZHEDULER_DB_POOL_SIZE")?.toIntOrNull() ?: 10,
                    )
                }

                "memory" -> StorageConfig.InMemory

                else -> error("ZHEDULER_STORAGE must be \"postgres\" or \"memory\", not \"$kind\".")
            }

            val port = env("ZHEDULER_PORT")?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: error("ZHEDULER_PORT must be a port number, not \"$text\".")
            } ?: DEFAULT_PORT

            ServerConfig(
                port = port,
                // Binding everywhere is the useful default for a server that is meant to be
                // reached, but an in-memory one is a development toy and stays on loopback.
                host = env("ZHEDULER_HOST")
                    ?: if (storage is StorageConfig.InMemory) "127.0.0.1" else "0.0.0.0",
                storage = storage,
                tokenLifetime = (env("ZHEDULER_TOKEN_TTL_DAYS")?.toLongOrNull() ?: 30L).days,
                allowedOrigins = env("ZHEDULER_CORS_ORIGINS")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                trustForwardedHeaders = env("ZHEDULER_TRUST_FORWARDED_FOR").toBoolean(),
                strictTransportSecurity = env("ZHEDULER_HSTS").toBoolean(),
            )
        }
    }
}
