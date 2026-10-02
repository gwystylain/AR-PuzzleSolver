package com.puzzlesolver.spell.server

/**
 * A token bucket: [perSecond] sustained, [burst] at once.
 *
 * Generous on purpose. Someone typing fast on the keypad sends about eight messages a
 * second, plus a ping every two; the limit is there for something that is not a person.
 */
class RateLimiter(
    private val perSecond: Double,
    private val burst: Double = perSecond * 2,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens = burst
    private var last = nanoTime()

    fun tryAcquire(): Boolean {
        val now = nanoTime()
        tokens = minOf(burst, tokens + (now - last) / 1e9 * perSecond)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/** Open connections, in total and per address, so one source cannot take every socket. */
class ConnectionLimiter(private val maxTotal: Int, private val maxPerAddress: Int) {
    private val byAddress = HashMap<String, Int>()
    private var total = 0

    @Synchronized
    fun tryAcquire(address: String): Boolean {
        val mine = byAddress[address] ?: 0
        if (total >= maxTotal || mine >= maxPerAddress) return false
        byAddress[address] = mine + 1
        total++
        return true
    }

    @Synchronized
    fun release(address: String) {
        val mine = byAddress[address] ?: return
        if (mine <= 1) byAddress.remove(address) else byAddress[address] = mine - 1
        total--
    }

    @get:Synchronized
    val open: Int get() = total
}
