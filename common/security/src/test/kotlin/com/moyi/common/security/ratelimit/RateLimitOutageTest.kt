package com.moyi.common.security.ratelimit

import com.moyi.common.security.SecurityTestApplication
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant

/**
 * ADR-0023's failure policy, against a Redis that is not there: port 1 on
 * localhost answers nothing, ever. No container base class on purpose.
 */
@SpringBootTest(
    classes = [SecurityTestApplication::class],
    properties = [
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms",
        "moyi.security.rate-limit.request-timeout=200ms",
    ],
)
@AutoConfigureMockMvc
class RateLimitOutageTest(
    @Autowired private val limiter: RateLimiter,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `with Redis unreachable the limiter allows the request, counts it, and does not stall`() {
        val before = meters.counter(RedisRateLimiter.BACKEND_UNAVAILABLE_METRIC).count()

        val started = Instant.now()
        val first = limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com")
        val firstTook = Duration.between(started, Instant.now())
        val secondStarted = Instant.now()
        val second = limiter.tryConsume(RateLimitBucket.AUTH_LOGIN_EMAIL, "ada@example.com")
        val secondTook = Duration.between(secondStarted, Instant.now())

        first.shouldBeInstanceOf<RateLimitDecision.Unavailable>()
        second.shouldBeInstanceOf<RateLimitDecision.Unavailable>()
        // One timeout is paid, then the hold answers at once for the interval.
        firstTook shouldBeLessThan Duration.ofSeconds(3)
        secondTook shouldBeLessThan Duration.ofMillis(50)
        meters.counter(RedisRateLimiter.BACKEND_UNAVAILABLE_METRIC).count() shouldBeGreaterThanOrEqual before + 2
    }

    @Test
    fun `a limited endpoint answers normally, without the headers it cannot fill in`() {
        val response =
            mockMvc
                .post("/api/v1/auth/register") { contentType = MediaType.APPLICATION_JSON }
                .andReturn()
                .response

        response.status shouldBe 200
        response.getHeader("X-RateLimit-Limit") shouldBe null
        response.getHeader("X-RateLimit-Remaining") shouldBe null
        response.getHeader("Retry-After") shouldBe null
    }
}
