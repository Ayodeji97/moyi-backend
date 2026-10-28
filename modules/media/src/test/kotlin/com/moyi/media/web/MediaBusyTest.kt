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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import javax.sql.DataSource

/**
 * A row lock not had within `lock_timeout` is `409 MEDIA_BUSY`, never an
 * unmapped `500` (review of #106). Production's pool sets 10 s; this context
 * sets 300 ms so the test does not wait out ten. The row is held by the test's
 * own open transaction, so "still held" is where the test is, not a timing.
 */
@SpringBootTest(
    classes = [MediaTestApplication::class],
    properties = ["spring.datasource.hikari.connection-init-sql=SET lock_timeout = '300ms'"],
)
@AutoConfigureMockMvc
internal class MediaBusyTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired private val dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `a completion and a deletion that cannot have the row are 409 MEDIA_BUSY, and change nothing`() {
        val ada = UUID.randomUUID()
        val photo = "fake photo bytes".toByteArray()
        val intent = client.intent(ada, photo)
        client.put(intent, photo) shouldBe 200
        val mediaId = intent["mediaId"].asString()

        HeldRow(dataSource, mediaId).use {
            for (response in listOf(client.complete(ada, mediaId), client.delete(ada, mediaId))) {
                response.status shouldBe 409
                response.contentAsString shouldContain "\"code\":\"MEDIA_BUSY\""
            }
        }

        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "PENDING_UPLOAD"
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 0
        client.complete(ada, mediaId).status shouldBe 200
    }
}
