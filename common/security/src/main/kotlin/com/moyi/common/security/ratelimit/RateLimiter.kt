package com.moyi.common.security.ratelimit

import java.time.Duration
import java.time.Instant

/**
 * The one question the rest of the application asks about rate limits: may
 * this key take one token from this bucket, now?
 *
 * A port, so that the identity services can consume a per-email bucket
 * without knowing that Redis or Bucket4j exist, and so that a test context
 * can answer from memory. **Never throws**: a limiter that cannot reach its
 * store answers [RateLimitDecision.Unavailable], and what to do about that is
 * decided once, in the implementation, not at every call site.
 *
 * [key] is the raw subject — an address, a lowercased email, a user id. The
 * implementation hashes it before it becomes a Redis key; nothing personal is
 * stored in the cache in the clear (doc 07 §5, NFR-044).
 */
fun interface RateLimiter {
    fun tryConsume(
        bucket: RateLimitBucket,
        key: String,
    ): RateLimitDecision
}

/** What the limiter decided, with the numbers the response headers need (doc 06 §4). */
sealed interface RateLimitDecision {
    /** A token was taken. [remaining] is what is left; [resetAt] is when the bucket is full again. */
    data class Allowed(
        val limit: Long,
        val remaining: Long,
        val resetAt: Instant,
    ) : RateLimitDecision

    /** The bucket was empty. [retryAfter] is the wait for one token; [resetAt] the wait for all of them. */
    data class Rejected(
        val limit: Long,
        val retryAfter: Duration,
        val resetAt: Instant,
    ) : RateLimitDecision

    /**
     * The store could not be reached. The request proceeds (ADR-0023: Redis
     * is a coordinator, never a source of truth, and a Redis outage must not
     * become a login outage), and no `X-RateLimit-*` headers are sent,
     * because there are no true numbers to put in them.
     */
    data object Unavailable : RateLimitDecision
}
