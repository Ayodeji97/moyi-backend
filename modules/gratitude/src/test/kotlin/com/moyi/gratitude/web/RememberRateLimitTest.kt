package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

/**
 * `remember:user` (ADR-0054, spec §3.6): thirty calls an hour, refused ones included, each user their own, because each
 * call can spend a provider request.
 *
 * Copied from `ReportRateLimitTest`'s setup, and for its reason it shares that class's cached context.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class], properties = ["moyi.security.rate-limit.enabled=true"])
@AutoConfigureMockMvc
internal class RememberRateLimitTest(
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
    fun `the 31st call in an hour is a 429`() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        repeat(30) { attempt(ada).status shouldBe 404 }
        val limited = attempt(ada)
        limited.status shouldBe 429
        limited.getHeader("X-RateLimit-Limit") shouldBe "30"
        limited.getHeader("X-RateLimit-Remaining") shouldBe "0"
        (limited.getHeader("Retry-After") != null) shouldBe true
        attempt(bea).status shouldBe 404
        attempt(bea).getHeader("X-RateLimit-Remaining") shouldBe "28"
    }

    private fun attempt(user: UUID) =
        mvc
            .get("/api/v1/bonds/${UUID.randomUUID()}/remember") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                param("q", "the soup")
            }.andReturn()
            .response
}
