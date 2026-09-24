package com.moyi.common.security.ratelimit

import com.moyi.common.security.SecurityTestApplication
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.RedisIntegrationTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The limiter against a real Valkey, with the clock in the test's hand.
 * These are the tests that prove the Bucket4j Lettuce adapter, built against
 * Lettuce 6, works over the Lettuce 7 Spring Boot 4 ships — a compile is not
 * that proof, a consumed token is.
 */
@SpringBootTest(classes = [SecurityTestApplication::class])
@Import(RedisRateLimiterTest.TimeConfiguration::class)
class RedisRateLimiterTest(
    @Autowired private val limiter: RateLimiter,
    @Autowired private val clock: MutableClock,
    @Autowired private val redis: StringRedisTemplate,
) : RedisIntegrationTest() {
    @AfterEach
    fun flush() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `five sign-in attempts are allowed and the sixth is refused with the wait for the next token`() {
        val start = clock.instant()

        val allowed = List(5) { limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com") }
        val sixth = limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com")

        allowed.map { it.shouldBeInstanceOf<RateLimitDecision.Allowed>().remaining } shouldBe listOf(4L, 3L, 2L, 1L, 0L)
        allowed.first().shouldBeInstanceOf<RateLimitDecision.Allowed>().limit shouldBe 5
        val rejected = sixth.shouldBeInstanceOf<RateLimitDecision.Rejected>()
        rejected.limit shouldBe 5
        // Greedy refill: one token every three minutes, all five in fifteen.
        rejected.retryAfter shouldBe Duration.ofMinutes(3)
        rejected.resetAt shouldBe start.plus(Duration.ofMinutes(15))
    }

    @Test
    fun `time refills the bucket`() {
        repeat(5) { limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com") }
        limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com").shouldBeInstanceOf<RateLimitDecision.Rejected>()

        clock.advance(Duration.ofMinutes(3))

        val afterOneRefill = limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com")
        afterOneRefill.shouldBeInstanceOf<RateLimitDecision.Allowed>().remaining shouldBe 0
    }

    @Test
    fun `keys and buckets are independent of each other`() {
        repeat(5) { limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com") }

        limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "grace@example.com").shouldBeInstanceOf<RateLimitDecision.Allowed>()
        limiter.tryConsume(RateLimitBucket.AUTH_RESET_EMAIL, "ada@example.com").shouldBeInstanceOf<RateLimitDecision.Allowed>()
    }

    @Test
    fun `what reaches Redis is a keyed hash under the documented prefix, and it expires`() {
        limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com")

        val keys = redis.keys("*")!!.toList()
        keys shouldHaveSize 1
        keys.single() shouldStartWith "rl:auth:login:email:"
        keys.single() shouldNotContain "ada"
        keys.single() shouldNotContain "example"
        // Doc 07 §5: "rolling" TTL — an idle bucket leaves no key behind.
        redis.getExpire(keys.single(), TimeUnit.SECONDS) shouldBeGreaterThan 0
    }

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }
}
