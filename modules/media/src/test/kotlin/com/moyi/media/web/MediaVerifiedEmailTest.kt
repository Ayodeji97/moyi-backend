package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.media.infra.FakeVerifiedEmails
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * The owner's ruling, 2026-10-29 (ADR-0049): media requires a verified email.
 * The two routes that make media refuse an unverified caller with bond's FR-002
 * answer, `403 EMAIL_NOT_VERIFIED`, and write nothing; the two that read and
 * let go of the caller's own media do not, because a person must always be
 * able to see and delete what is theirs. (The third gated path, a claim by an
 * entry, is `gratitude`'s `EntryPhotoTest`.)
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
internal class MediaVerifiedEmailTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val emails: FakeVerifiedEmails,
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)
    private val ada = UUID.randomUUID()
    private val photo = "fake photo bytes, not a real jpeg".toByteArray()

    @AfterEach
    fun clear() {
        emails.clear()
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `an unverified caller's upload intent is 403 EMAIL_NOT_VERIFIED, and no asset is written`() {
        emails.unverified(ada)

        val refused = intent(ada)

        refused.status shouldBe 403
        refused.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
        jdbc.queryForObject("SELECT count(*) FROM media_assets", Int::class.java) shouldBe 0
    }

    @Test
    fun `once verified, the same caller's upload intent is 201`() {
        emails.unverified(ada)
        intent(ada).status shouldBe 403

        emails.verify(ada)

        intent(ada).status shouldBe 201
    }

    @Test
    fun `an unverified caller's completion is 403, changes nothing, and the same key completes once verified`() {
        val intent = client.intent(ada, photo)
        client.put(intent, photo) shouldBe 200
        val mediaId = intent["mediaId"].asString()
        val key = UUID.randomUUID().toString()
        emails.unverified(ada)

        val refused = client.complete(ada, mediaId, key)

        refused.status shouldBe 403
        refused.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "PENDING_UPLOAD"
        jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java) shouldBe 0

        emails.verify(ada)

        client.complete(ada, mediaId, key).status shouldBe 200
    }

    @Test
    fun `an unverified caller may still read and delete their own media`() {
        val mediaId = client.upload(ada, photo)
        emails.unverified(ada)

        client.get(ada, mediaId).status shouldBe 200
        client.delete(ada, mediaId).status shouldBe 204
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "DELETED"
    }

    private fun intent(owner: UUID): MockHttpServletResponse =
        mvc
            .post("/api/v1/media/upload-intent") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(owner).token}")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"kind":"IMAGE","contentType":"image/jpeg","sizeBytes":${photo.size},"checksum":"${MediaClient.sha256(photo)}"}"""
            }.andReturn()
            .response
}
