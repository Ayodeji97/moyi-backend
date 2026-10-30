package com.moyi.common.security.ratelimit

import com.moyi.common.security.PersonalDataHasher
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.TimeMeter
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Token buckets in Redis, through Bucket4j (doc 25 D6, doc 07 §5).
 *
 * **What is ours and what is the library's**, in the spirit of D5: the bucket
 * arithmetic — refill, compare-and-swap against concurrent consumers, the
 * Lua that makes one consume atomic — is Bucket4j's, over the Lettuce
 * connection Spring Boot already configures. What is written here is the
 * key (hashed, prefixed per doc 07 §5), the clock (the injected one, so a
 * test can refill a bucket by moving time), and the failure policy.
 *
 * **Fail-open, and loudly.** Redis is a coordinator, never a source of truth
 * (doc 07 §5): if it cannot be reached, the answer is [RateLimitDecision.Unavailable]
 * and the request goes through. The alternative — refusing every login while
 * the cache is down — turns a cache outage into an authentication outage,
 * and the controls that remain (Argon2id's cost, the silent exponential
 * lockout in Postgres) still stand. It is loud in two ways: a WARN, at most
 * once per [RateLimitProperties.retryInterval] so an outage is one line a
 * second and not one line a request; and the counter
 * `moyi.rate_limit.backend_unavailable`, which is what an alert watches.
 * For [RateLimitProperties.retryInterval] after a failure, Redis is not tried
 * at all; when the interval is up, **one** caller probes it while every other
 * stays failed open, so a dead Redis costs one timeout per interval and not
 * one per in-flight login (Codex's P2 on #33: without that gate, every
 * request that saw the expired hold probed together).
 *
 * **Connected lazily, and again whenever the connection is gone.** The
 * application must boot with Redis down; the first consume connects, and a
 * failed connect is retried on the next attempt after the interval. Lettuce
 * reconnects a connection whose server went away, but not one whose client
 * was shut down: [LettuceConnectionFactory] is a `SmartLifecycle`, and a
 * `stop()` (a context paused between test classes, as Spring 7's test
 * framework does, or a lifecycle stop in production) destroys its client and
 * every connection made from it, and `start()` makes a new client. The
 * connection used to be built once, in a `lazy`, and was then closed for
 * good: every request failed open, silently but for the WARN, for the life of
 * the process (review of #118, P2-d). So the connection is now checked before
 * each use: rebuilt when it is closed or when the factory's client is no
 * longer the one it was made from (`RedisRateLimiterTest`, "a factory stopped
 * and started again").
 */
class RedisRateLimiter(
    private val connections: LettuceConnectionFactory,
    private val hasher: PersonalDataHasher,
    private val clock: Clock,
    private val properties: RateLimitProperties,
    private val meters: MeterRegistry,
) : RateLimiter {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unavailable: Counter = meters.counter(BACKEND_UNAVAILABLE_METRIC)

    /**
     * Until when Redis is not worth asking; `null` while Redis is believed
     * healthy. Starts in the past rather than `null`, so that first contact
     * goes through the probe gate too: the lazy connection is a synchronised
     * initialiser, and twenty concurrent first callers against a Redis that
     * does not answer would otherwise queue on it and each pay the connect
     * timeout in turn (found in review of #33).
     */
    private val backOffUntil = AtomicReference<Instant?>(Instant.EPOCH)

    /** Set by the one caller allowed to probe once the hold has expired. */
    private val probing = AtomicBoolean(false)

    /** The client, the connection made from it and the buckets over it; `null` until the first connect. */
    private class Connected(
        val client: RedisClient,
        val connection: StatefulRedisConnection<ByteArray, ByteArray>,
        val proxies: ProxyManager<ByteArray>,
    )

    @Volatile
    private var connected: Connected? = null

    override fun tryConsume(
        bucket: RateLimitBucket,
        key: String,
    ): RateLimitDecision {
        val now = clock.instant()
        val hold = backOffUntil.get()
        return when {
            hold == null -> {
                consume(bucket, key, now)
            }

            now.isBefore(hold) -> {
                heldUnavailable()
            }

            probing.compareAndSet(false, true) -> {
                try {
                    consume(bucket, key, now)
                } finally {
                    probing.set(false)
                }
            }

            else -> {
                heldUnavailable()
            }
        }
    }

    /** Every request that passes without a limit is counted, held or not. */
    private fun heldUnavailable(): RateLimitDecision {
        unavailable.increment()
        return RateLimitDecision.Unavailable
    }

    private fun consume(
        bucket: RateLimitBucket,
        key: String,
        now: Instant,
    ): RateLimitDecision =
        try {
            val probe =
                proxies()
                    .builder()
                    .build(redisKey(bucket, key)) { configurationOf(bucket) }
                    .tryConsumeAndReturnRemaining(1)
            backOffUntil.set(null)
            decide(bucket, now, probe)
        } catch (
            // Lettuce's RedisException family, Bucket4j's own TimeoutException,
            // a connection factory that is not started: every one of them
            // means "no answer from Redis", and the policy for that is one
            // policy. Anything narrower is a login outage waiting for an
            // exception type nobody listed.
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            unavailable(now, failure)
        }

    private fun decide(
        bucket: RateLimitBucket,
        now: Instant,
        probe: ConsumptionProbe,
    ): RateLimitDecision {
        val resetAt = now.plusNanos(probe.nanosToWaitForReset)
        return if (probe.isConsumed) {
            RateLimitDecision.Allowed(limit = bucket.capacity, remaining = probe.remainingTokens, resetAt = resetAt)
        } else {
            meters.counter(REJECTIONS_METRIC, "bucket", bucket.id).increment()
            RateLimitDecision.Rejected(
                limit = bucket.capacity,
                retryAfter = Duration.ofNanos(probe.nanosToWaitForRefill),
                resetAt = resetAt,
            )
        }
    }

    private fun unavailable(
        now: Instant,
        failure: RuntimeException,
    ): RateLimitDecision {
        unavailable.increment()
        // Whoever moves the hold forward logs. Twenty in-flight requests that
        // fail together when Redis dies race on this compare-and-set, and
        // exactly one of them wins the WARN.
        val previous = backOffUntil.get()
        val expired = previous == null || !now.isBefore(previous)
        if (!expired || !backOffUntil.compareAndSet(previous, now.plus(properties.retryInterval))) {
            return RateLimitDecision.Unavailable
        }
        log.warn(
            "Rate limiting is unavailable: Redis did not answer ({}: {}). Requests are being allowed through " +
                "without limits for the next {}; see the moyi.rate_limit.backend_unavailable counter.",
            failure.javaClass.simpleName,
            failure.message,
            properties.retryInterval,
        )
        return RateLimitDecision.Unavailable
    }

    /**
     * The buckets over a live connection from the factory's current client:
     * the one made before if it still is, a new one otherwise. A factory that
     * is stopped has no client, and `requiredNativeClient` throws; that is the
     * outage the caller already treats as one. A failed connect leaves nothing
     * cached, so the next probe tries again.
     */
    private fun proxies(): ProxyManager<ByteArray> {
        val client =
            connections.requiredNativeClient as? RedisClient
                ?: error("Rate limiting expects a standalone Redis client; got ${connections.requiredNativeClient.javaClass.name}")
        connected?.takeIf { it.client === client && it.connection.isOpen }?.let { return it.proxies }
        return synchronized(this) {
            val current = connected
            if (current != null && current.client === client && current.connection.isOpen) {
                current.proxies
            } else {
                // The old one is closed, or belongs to a client that was shut down; closing it again is harmless.
                current?.let { runCatching { it.connection.close() } }
                connect(client).also { connected = it }.proxies
            }
        }
    }

    private fun connect(client: RedisClient): Connected {
        val connection = client.connect(ByteArrayCodec.INSTANCE)
        val proxies =
            Bucket4jLettuce
                .casBasedBuilder(connection)
                .clientClock(ClockTimeMeter(clock))
                .requestTimeout(properties.requestTimeout)
                // Doc 07 §5: "rolling" — a bucket that has refilled to the brim
                // has nothing to remember, and its key goes with it.
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
                .build()
        return Connected(client, connection, proxies)
    }

    /** `rl:{bucket}:{key}` per doc 07 §5, with the key hashed so no address or email is ever a Redis key. */
    private fun redisKey(
        bucket: RateLimitBucket,
        key: String,
    ): ByteArray = "$KEY_PREFIX${bucket.id}:${hasher.hash(key)}".toByteArray(Charsets.UTF_8)

    private fun configurationOf(bucket: RateLimitBucket): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(bucket.capacity)
                    .refillGreedy(bucket.capacity, bucket.period)
                    .build(),
            ).build()

    /** Bucket4j reads time through this; binding it to the injected clock is what makes the limiter testable without sleeping. */
    private class ClockTimeMeter(
        private val clock: Clock,
    ) : TimeMeter {
        override fun currentTimeNanos(): Long {
            val now = clock.instant()
            return now.epochSecond * NANOS_PER_SECOND + now.nano
        }

        override fun isWallClockBased(): Boolean = true
    }

    companion object {
        const val BACKEND_UNAVAILABLE_METRIC = "moyi.rate_limit.backend_unavailable"
        const val REJECTIONS_METRIC = "moyi.rate_limit.rejections"
        private const val KEY_PREFIX = "rl:"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val KEEP_AFTER_FULL: Duration = Duration.ofMinutes(1)
    }
}
