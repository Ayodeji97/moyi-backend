package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/** Doc 06 §4's `media:intent`: thirty intents an hour per user, a refused one included; another user keeps theirs. */
@SpringBootTest(classes = [MediaTestApplication::class], properties = ["moyi.security.rate-limit.enabled=true"])
@AutoConfigureMockMvc
internal class MediaRateLimitTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        jdbc.execute("TRUNCATE TABLE media_assets")
    }

    @Test
    fun `thirty refused intents spend the hour's allowance, the thirty-first is 429, and another user is unaffected`() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        val ada = UUID.randomUUID()
        val bea = UUID.randomUUID()
        repeat(30) { attempt(ada).status shouldBe 415 }

        val limited = attempt(ada)
        limited.status shouldBe 429
        limited.contentAsString shouldContain "\"code\":\"RATE_LIMITED\""
        limited.getHeader("X-RateLimit-Limit") shouldBe "30"
        limited.getHeader("X-RateLimit-Remaining") shouldBe "0"
        limited.getHeader("Retry-After") shouldBe "120"
        attempt(bea).status shouldBe 415
    }

    /** A declaration refused at once (an unsupported type): the bucket is spent before the body is judged. */
    private fun attempt(user: UUID) =
        mvc
            .post("/api/v1/media/upload-intent") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"kind":"IMAGE","contentType":"image/gif","sizeBytes":10,"checksum":"$ZEROS"}"""
            }.andReturn()
            .response
}

/** Thirty-two zero bytes in base64: a well-formed digest of nothing in particular. */
private const val ZEROS = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
