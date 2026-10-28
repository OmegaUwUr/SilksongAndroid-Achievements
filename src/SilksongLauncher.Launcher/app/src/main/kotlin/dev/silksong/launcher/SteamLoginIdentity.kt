package dev.silksong.launcher

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** Unique within this launcher process; a randomized start separates app/device restarts.
 * JavaSteam's default uses the same bind address for every connection and replaces
 * another live session when the library scanner, downloader and achievement service overlap.
 */
internal object SteamLoginIdentity {
    private val next = AtomicInteger(SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1)
    fun allocate(): Int = next.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }
}
