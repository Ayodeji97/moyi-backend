package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `reports:create:user` (ADR-0053 decision 9): ten reports a day, refused ones included, each user their own.
 *
 * A context of its own (the application name only tells it apart): sharing `EntryRateLimitTest`'s cached one, the
 * full build found the limiter's Redis connection closed by the time this class ran ("Connection is closed", every
 * attempt for twenty seconds), so every request was let through. Run alone, the shared one passed.
 */
@SpringBootTest(
    classes = [GratitudeTestApplication::class],
    properties = ["moyi.security.rate-limit.enabled=true", "spring.application.name=report-rate-limit"],
)
@AutoConfigureMockMvc
internal class ReportRateLimitTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory

    @AfterEach
    fun clear() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        users.clear()
    }

    @Test
    fun `ten reports consume the daily allowance and another user keeps theirs`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        awaitLimiter(users.verified("Cara"))
        redis.execInContainer("valkey-cli", "FLUSHALL")
        repeat(10) { attempt(ada).status shouldBe 404 }
        val limited = attempt(ada)
        limited.status shouldBe 429
        limited.getHeader("X-RateLimit-Limit") shouldBe "10"
        limited.getHeader("X-RateLimit-Remaining") shouldBe "0"
        (limited.getHeader("Retry-After") != null) shouldBe true
        attempt(bea).status shouldBe 404
    }

    /**
     * Until the limiter answers: it fails open for five seconds after Redis
     * does not (ADR-0023), and a first request in a fresh context may meet
     * that. What is waited on is the limiter's own answer (its header).
     */
    private fun awaitLimiter(user: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMITER_WAIT_SECONDS)
        while (attempt(user).getHeader("X-RateLimit-Limit") == null) {
            check(System.nanoTime() < deadline) { "the rate limiter never answered" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun attempt(user: UUID) =
        mvc
            .post("/api/v1/entries/${UUID.randomUUID()}/report") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"reason":"OTHER"}"""
            }.andReturn()
            .response

    private companion object {
        const val LIMITER_WAIT_SECONDS = 20L
        const val POLL_MILLIS = 250L
    }
}
