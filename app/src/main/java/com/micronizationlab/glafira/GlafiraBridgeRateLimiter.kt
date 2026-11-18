package com.micronizationlab.glafira

/**
 * Small bounded token-bucket limiter for Bridge ingress.
 *
 * Use separate instances for remote address and authenticated device ID. The caller must
 * invoke the address limiter before parsing a request and the device limiter after
 * credential authentication. Supply monotonic nanoseconds (System.nanoTime), not wall time.
 *
 * When the identity table is full and no idle entry can be evicted, the limiter fails
 * closed for new identities rather than evicting an active client's budget.
 */
class GlafiraBridgeRateLimiter(
    private val maxTrackedIdentities: Int,
    private val bucketCapacity: Int,
    private val refillTokensPerSecond: Double,
    private val idleTimeoutNanos: Long
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefillNanos: Long,
        var lastSeenNanos: Long
    )

    private val buckets = LinkedHashMap<String, Bucket>()

    init {
        require(maxTrackedIdentities in 1..100_000)
        require(bucketCapacity in 1..10_000)
        require(refillTokensPerSecond.isFinite() && refillTokensPerSecond > 0.0)
        require(idleTimeoutNanos > 0L)
    }

    @Synchronized
    fun tryAcquire(identity: String, nowNanos: Long = System.nanoTime()): Boolean {
        if (!isValidIdentity(identity) || nowNanos <= 0L) return false

        evictIdle(nowNanos)
        val bucket = buckets[identity]
        if (bucket == null) {
            if (buckets.size >= maxTrackedIdentities) return false
            buckets[identity] = Bucket(
                tokens = (bucketCapacity - 1).toDouble(),
                lastRefillNanos = nowNanos,
                lastSeenNanos = nowNanos
            )
            return true
        }

        if (nowNanos < bucket.lastRefillNanos || nowNanos < bucket.lastSeenNanos) return false

        val elapsedNanos = nowNanos - bucket.lastRefillNanos
        if (elapsedNanos > 0L) {
            val replenished = elapsedNanos.toDouble() / NANOS_PER_SECOND * refillTokensPerSecond
            bucket.tokens = minOf(bucketCapacity.toDouble(), bucket.tokens + replenished)
            bucket.lastRefillNanos = nowNanos
        }
        bucket.lastSeenNanos = nowNanos

        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    @Synchronized
    fun trackedIdentityCount(): Int = buckets.size

    private fun evictIdle(nowNanos: Long) {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next().value
            if (nowNanos >= bucket.lastSeenNanos &&
                nowNanos - bucket.lastSeenNanos >= idleTimeoutNanos
            ) iterator.remove()
        }
    }

    private fun isValidIdentity(value: String): Boolean =
        value.length in 1..128 && value.none(Char::isISOControl)

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
