package com.puzzlesolver.spell.server

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Everything about the server that is set from outside, read from the environment so the
 * container is configured the way every other app on the NAS is.
 */
data class ServerConfig(
    val port: Int = 8080,
    val dictionary: Path = Paths.get("/data/csw24.txt"),
    /**
     * Take the client's address from `X-Forwarded-For`, as set by the reverse proxy in front.
     * Only safe when nothing can reach the server except through that proxy, since anyone
     * else can put whatever they like in the header. It only feeds the per-address limit.
     */
    val trustForwarded: Boolean = false,
    /** How long a seat is held for a player whose connection dropped. */
    val graceMillis: Long = 120_000,
    /**
     * How long a dropped player still shows as online. Long enough to cover a phone
     * switching networks and reconnecting, so nobody sees it flicker.
     */
    val offlineAfterMillis: Long = 3_000,
    /** A connection that sends nothing for this long is dead; the app pings every 2 s. */
    val idleTimeoutMillis: Long = 20_000,
    val maxLobbies: Int = 50,
    val maxConnections: Int = 250,
    /**
     * Connections from one address. Not one: a cellular carrier puts many phones behind one
     * address, and a whole team on the venue's Wi-Fi shares one too.
     */
    val maxConnectionsPerAddress: Int = 20,
    /** Messages a connection may send per second, sustained; bursts of twice that are allowed. */
    val messagesPerSecond: Double = 20.0,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val d = ServerConfig()
            fun int(name: String, default: Int) = env[name]?.trim()?.toIntOrNull() ?: default
            fun long(name: String, default: Long) = env[name]?.trim()?.toLongOrNull() ?: default
            return ServerConfig(
                port = int("SPELL_PORT", d.port),
                dictionary = env["SPELL_DICTIONARY"]?.let { Paths.get(it) } ?: d.dictionary,
                trustForwarded = env["SPELL_TRUST_FORWARDED"]?.trim()?.lowercase() in setOf("1", "true", "yes"),
                graceMillis = long("SPELL_GRACE_SECONDS", d.graceMillis / 1000) * 1000,
                maxLobbies = int("SPELL_MAX_LOBBIES", d.maxLobbies),
                maxConnections = int("SPELL_MAX_CONNECTIONS", d.maxConnections),
                maxConnectionsPerAddress = int("SPELL_MAX_CONNECTIONS_PER_ADDRESS", d.maxConnectionsPerAddress),
            )
        }
    }
}
