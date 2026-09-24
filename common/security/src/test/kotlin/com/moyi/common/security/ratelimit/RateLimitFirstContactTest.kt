package com.moyi.common.security.ratelimit

import com.moyi.common.security.SecurityTestApplication
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The first burst of requests after boot, with a Redis that never answers:
 * a non-routable address, so each connect attempt pays the full connect
 * timeout rather than being refused at once.
 *
 * Found in review of #33: the lazy connection is a synchronised initialiser,
 * so before any failure has set the hold, concurrent callers queue on it and
 * each re-runs the failing connect in turn — twenty callers, twenty timeouts,
 * one after another. The gate that already serialises probes once the hold
 * has expired has to cover first contact too.
 */
@SpringBootTest(
    classes = [SecurityTestApplication::class],
    properties = [
        "spring.data.redis.host=10.255.255.1",
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms",
        "moyi.security.rate-limit.request-timeout=200ms",
    ],
)
class RateLimitFirstContactTest(
    @Autowired private val limiter: RateLimiter,
) {
    @Test
    fun `twenty concurrent first calls cost one connect timeout between them, not twenty in a row`() {
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        val gate = CountDownLatch(1)
        val started = Instant.now()
        (1..CONCURRENCY)
            .map {
                pool.submit {
                    gate.await()
                    limiter
                        .tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com")
                        .shouldBeInstanceOf<RateLimitDecision.Unavailable>()
                }
            }.also { gate.countDown() }
            .forEach { it.get() }
        pool.shutdown()

        // One caller connects and times out (~200 ms); the rest fail open at once.
        Duration.between(started, Instant.now()) shouldBeLessThan Duration.ofMillis(1500)
    }

    private companion object {
        const val CONCURRENCY = 20
    }
}
