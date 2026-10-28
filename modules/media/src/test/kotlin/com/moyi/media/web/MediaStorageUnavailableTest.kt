package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * Production until the bucket exists (ADR-0047): the provider is UNAVAILABLE,
 * the context starts with no media credentials, an upload is refused with 503
 * and writes nothing, and the rest of the routes answer as for an asset that
 * does not exist, which none can.
 */
@SpringBootTest(classes = [MediaTestApplication::class], properties = ["moyi.media.storage.provider=unavailable"])
@AutoConfigureMockMvc
internal class MediaStorageUnavailableTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `an intent is 503 MEDIA_STORAGE_UNAVAILABLE and no asset is recorded`() {
        val ada = UUID.randomUUID()
        val response =
            mvc
                .post("/api/v1/media/upload-intent") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}")
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"kind":"IMAGE","contentType":"image/png","sizeBytes":10,"checksum":"$ZEROS"}"""
                }.andReturn()
                .response

        response.status shouldBe 503
        response.contentAsString shouldContain "\"code\":\"MEDIA_STORAGE_UNAVAILABLE\""
        jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE owner_user_id = ?", Int::class.java, ada) shouldBe 0
        mvc
            .get("/api/v1/media/${UUID.randomUUID()}") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}") }
            .andReturn()
            .response.status shouldBe 404
    }
}

/** Thirty-two zero bytes in base64: a well-formed digest of nothing in particular. */
private const val ZEROS = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
