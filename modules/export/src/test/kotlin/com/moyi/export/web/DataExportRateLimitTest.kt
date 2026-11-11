package com.moyi.export.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.export.infra.ExportTestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/** `account:export:user` (ADR-0059 decision 4): three new exports a day, repeats free, each user their own. */
@SpringBootTest(classes = [ExportTestApplication::class], properties = ["moyi.security.rate-limit.enabled=true"])
@AutoConfigureMockMvc
internal class DataExportRateLimitTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        jdbc.execute("TRUNCATE TABLE data_exports, export_archive_deletions")
    }

    @Test
    fun `only a new export spends the day's three, a repeat while one is prepared spends none, and the fourth new one is 429`() {
        redis.execInContainer("valkey-cli", "FLUSHALL")
        val ada = UUID.randomUUID()
        repeat(3) {
            // Five asks for each export: one new one and four repeats while it is being prepared.
            repeat(5) { request(ada).status shouldBe 202 }
            jdbc.update("UPDATE data_exports SET status = 'FAILED', ended_at = now() WHERE user_id = ? AND status = 'PREPARING'", ada)
        }

        val limited = request(ada)

        limited.status shouldBe 429
        limited.getHeader("X-RateLimit-Limit") shouldBe "3"
        (limited.getHeader("Retry-After")!!.toLong() > 0) shouldBe true
        jdbc.queryForObject("SELECT count(*) FROM data_exports WHERE user_id = ?", Int::class.java, ada) shouldBe 3
        request(UUID.randomUUID()).status shouldBe 202
    }

    private fun request(user: UUID) =
        mvc
            .post("/api/v1/me/export") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response
}
