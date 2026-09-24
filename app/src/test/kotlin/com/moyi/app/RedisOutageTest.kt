package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Doc 07 §5's promise, at the composition root: "losing Redis entirely
 * degrades performance and nothing else". Postgres is real; Redis is a port
 * nothing listens on. The application boots, health is UP — so kamal-proxy
 * keeps routing to it — and a limited endpoint still answers (ADR-0023).
 *
 * [PostgresIntegrationTest] rather than `IntegrationTest`, deliberately:
 * the whole point is that there is no Redis.
 */
@SpringBootTest(
    properties = [
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedisOutageTest(
    @Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {
    @Test
    fun `health is UP without Redis`() {
        val response = mockMvc.get("/actuator/health").andReturn().response

        response.status shouldBe 200
        response.contentAsString shouldContain "\"status\":\"UP\""
    }

    @Test
    fun `a rate-limited endpoint still answers, without limit headers`() {
        // A validation failure is enough: the interceptor runs before the
        // body is read, so reaching a 422 proves the limiter let it through.
        val response =
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"not-an-address","password":"x","displayName":"","acceptedTermsVersion":"1","over18":true}"""
                }.andReturn()
                .response

        response.status shouldBe 422
        response.getHeader("X-RateLimit-Limit") shouldBe null
    }
}
