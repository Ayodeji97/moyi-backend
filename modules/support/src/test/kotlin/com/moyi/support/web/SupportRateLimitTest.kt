package com.moyi.support.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.support.infra.SupportTestApplication
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

/** `support:create:user` (doc 06 §4, ADR-0057): five messages a day, refused ones included, each user their own. */
@SpringBootTest(classes = [SupportTestApplication::class], properties = ["moyi.security.rate-limit.enabled=true"])
@AutoConfigureMockMvc
internal class SupportRateLimitTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
) : IntegrationTest() {
    @AfterEach
    fun clear() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
    }

    @Test
    fun `five messages consume the daily allowance, the sixth is 429 with Retry-After, and another user keeps theirs`() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        val ada = UUID.randomUUID()
        val bea = UUID.randomUUID()
        repeat(5) { attempt(ada).status shouldBe 422 }
        val limited = attempt(ada)
        limited.status shouldBe 429
        limited.getHeader("X-RateLimit-Limit") shouldBe "5"
        limited.getHeader("X-RateLimit-Remaining") shouldBe "0"
        (limited.getHeader("Retry-After")!!.toLong() > 0) shouldBe true
        val other = attempt(bea)
        other.status shouldBe 422
        other.getHeader("X-RateLimit-Remaining") shouldBe "4"
    }

    /** A body the edge refuses: the bucket is spent before the body is read, so a refusal costs one too, and files nothing. */
    private fun attempt(user: UUID) =
        mvc
            .post("/api/v1/support/messages") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"subject":"","body":""}"""
            }.andReturn()
            .response
}
