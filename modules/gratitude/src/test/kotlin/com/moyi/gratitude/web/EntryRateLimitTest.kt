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

/** The write bucket bounds refused submissions too, independently for each authenticated user. */
@SpringBootTest(classes = [GratitudeTestApplication::class], properties = ["moyi.security.rate-limit.enabled=true"])
@AutoConfigureMockMvc
internal class EntryRateLimitTest(
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
    fun `twenty refused writes consume the daily allowance and another user keeps theirs`() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        repeat(20) { attempt(ada).status shouldBe 404 }
        val limited = attempt(ada)
        limited.status shouldBe 429
        limited.getHeader("X-RateLimit-Limit") shouldBe "20"
        limited.getHeader("X-RateLimit-Remaining") shouldBe "0"
        (limited.getHeader("Retry-After") != null) shouldBe true
        attempt(bea).status shouldBe 404
    }

    private fun attempt(user: UUID) =
        mvc
            .post("/api/v1/bonds/${UUID.randomUUID()}/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thanks"}"""
            }.andReturn()
            .response
}
