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

/**
 * `reports:create:user` (ADR-0053 decision 9): ten reports a day, refused ones included, each user their own.
 *
 * It shares `EntryRateLimitTest`'s cached context. In the full build that context had been paused and started again
 * by the time this class ran, and the limiter, holding a connection from the stopped factory, let every request through
 * (review of #118, P2-d). The limiter now makes its connection again; `RedisRateLimiterTest` holds that.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class], properties = ["moyi.security.rate-limit.enabled=true"])
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
        redis.execInContainer("valkey-cli", "FLUSHALL")
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        repeat(10) { attempt(ada).status shouldBe 404 }
        val limited = attempt(ada)
        limited.status shouldBe 429
        limited.getHeader("X-RateLimit-Limit") shouldBe "10"
        limited.getHeader("X-RateLimit-Remaining") shouldBe "0"
        (limited.getHeader("Retry-After") != null) shouldBe true
        attempt(bea).status shouldBe 404
        attempt(bea).getHeader("X-RateLimit-Remaining") shouldBe "8"
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
}
