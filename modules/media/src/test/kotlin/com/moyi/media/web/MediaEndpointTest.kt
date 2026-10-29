package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * The four `/media` routes through the real chain (security, limiter,
 * Idempotency-Key), against Postgres and MinIO, and an upload made the way a
 * phone makes it: a PUT to the URL the intent handed back, never through this
 * server (FR-044).
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
internal class MediaEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val ada = UUID.randomUUID()
    private val photo = "fake photo bytes, not a real jpeg".toByteArray()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `an intent, a PUT straight to the storage and a completion make an uploaded asset and one content-free event`(
        output: CapturedOutput,
    ) {
        val intent = intent(ada, photo)
        intent.status shouldBe 201
        val body = json.readTree(intent.contentAsString)
        val mediaId = UUID.fromString(body["mediaId"].asString())
        val uploadUrl = body["uploadUrl"].asString()
        mediaId.version() shouldBe 4
        body["expiresAt"].asString() shouldBe "2026-10-28T09:15:00Z"
        body["requiredHeaders"]["content-type"].asString() shouldBe "image/jpeg"
        body["requiredHeaders"]["x-amz-checksum-sha256"].asString() shouldBe sha256(photo)

        val row = jdbc.queryForMap("SELECT owner_user_id, status, storage_key, size_bytes FROM media_assets WHERE id = ?", mediaId)
        row["owner_user_id"] shouldBe ada
        row["status"] shouldBe "PENDING_UPLOAD"
        row["size_bytes"] shouldBe photo.size.toLong()
        val key = row["storage_key"] as String
        key shouldStartWith "in/"
        UUID.fromString(key.removePrefix("in/")).version() shouldBe 4
        key shouldNotContain ada.toString()
        key shouldNotContain mediaId.toString()

        put(body, photo) shouldBe 200
        val completed = complete(ada, mediaId.toString())
        completed.status shouldBe 200
        completed.contentAsString shouldContain "\"status\":\"UPLOADED\""
        completed.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe null

        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, mediaId) shouldBe "UPLOADED"
        val events = jdbc.queryForList("SELECT aggregate_type, aggregate_id, event_type, payload::text FROM outbox_events")
        events.size shouldBe 1
        events[0]["aggregate_type"] shouldBe "Media"
        events[0]["aggregate_id"] shouldBe mediaId
        events[0]["event_type"] shouldBe "MediaUploaded"
        events[0]["payload"] as String shouldNotContain key

        get(ada, mediaId.toString()).contentAsString.let {
            it shouldContain "\"status\":\"UPLOADED\""
            it shouldContain "\"kind\":\"IMAGE\""
            it shouldNotContain "in/"
        }
        // Neither the URL's signature nor the key reaches a log line.
        val signature =
            URI
                .create(uploadUrl)
                .query
                .split('&')
                .first { it.startsWith("X-Amz-Signature=") }
        output.all shouldNotContain signature
        output.all shouldNotContain key
    }

    @Test
    fun `a completion replayed under its key is the same answer, and a new key changes nothing and publishes nothing`() {
        val mediaId = uploaded(ada)
        val key = UUID.randomUUID().toString()
        complete(ada, mediaId, key).status shouldBe 200

        val replay = complete(ada, mediaId, key)
        replay.status shouldBe 200
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldContain "\"status\":\"UPLOADED\""

        val again = complete(ada, mediaId)
        again.status shouldBe 200
        again.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe null
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MediaUploaded'", Int::class.java) shouldBe 1
    }

    @Test
    fun `completing before the PUT is 409, changes nothing, and the same key completes once the bytes are there`() {
        val body = json.readTree(intent(ada, photo).contentAsString)
        val mediaId = body["mediaId"].asString()
        val key = UUID.randomUUID().toString()

        val early = complete(ada, mediaId, key)
        early.status shouldBe 409
        early.contentAsString shouldContain "\"code\":\"MEDIA_NOT_UPLOADED\""
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "PENDING_UPLOAD"
        jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java) shouldBe 0

        put(body, photo) shouldBe 200
        val later = complete(ada, mediaId, key)
        later.status shouldBe 200
        later.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe null
    }

    @Test
    fun `an object of another length under the key, put there behind the URL's back, does not complete`() {
        val body = json.readTree(intent(ada, photo).contentAsString)
        val mediaId = body["mediaId"].asString()
        val key = jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!
        putBehindTheApi(key, photo + photo)

        complete(ada, mediaId).status shouldBe 409
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "PENDING_UPLOAD"
    }

    @Test
    fun `an object of the declared length with other bytes, put there behind the URL's back, does not complete`() {
        val body = json.readTree(intent(ada, photo).contentAsString)
        val mediaId = body["mediaId"].asString()
        val key = jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!
        // A plain PUT with a checksum header MinIO records: the same length, another digest.
        val forged = photo.copyOf().also { it[0] = 'X'.code.toByte() }
        admin.putObject(
            software.amazon.awssdk.services.s3.model.PutObjectRequest
                .builder()
                .bucket(BUCKET)
                .key(key)
                .checksumSHA256(sha256(forged))
                .build(),
            software.amazon.awssdk.core.sync.RequestBody
                .fromBytes(forged),
        )

        complete(ada, mediaId).status shouldBe 409
    }

    @Test
    fun `completion requires an Idempotency-Key`() {
        val mediaId = uploaded(ada)
        val response =
            mvc
                .post("/api/v1/media/$mediaId/complete") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
                .andReturn()
                .response
        response.status shouldBe 422
        response.contentAsString shouldContain "Idempotency-Key"
    }

    @Test
    fun `the owner deletes an asset, 204, its object is gone, and every route then answers 404`() {
        val mediaId = uploaded(ada)
        val key = jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!
        stored(key) shouldBe true

        delete(ada, mediaId).status shouldBe 204

        stored(key) shouldBe false
        val row = jdbc.queryForMap("SELECT status, deleted_at FROM media_assets WHERE id = ?::uuid", mediaId)
        row["status"] shouldBe "DELETED"
        (row["deleted_at"] != null) shouldBe true
        get(ada, mediaId).status shouldBe 404
        delete(ada, mediaId).status shouldBe 404
        complete(ada, mediaId).status shouldBe 404
    }

    @Test
    fun `a replay of a completion after the asset was deleted is the read's 404, not the stored 200`() {
        val mediaId = uploaded(ada)
        val key = UUID.randomUUID().toString()
        complete(ada, mediaId, key).status shouldBe 200
        delete(ada, mediaId).status shouldBe 204

        complete(ada, mediaId, key).status shouldBe 404
    }

    @Test
    fun `an unfinished upload may be deleted too`() {
        val mediaId = json.readTree(intent(ada, photo).contentAsString)["mediaId"].asString()
        delete(ada, mediaId).status shouldBe 204
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "DELETED"
    }

    @Test
    fun `each refusal of a declaration has its own status and code, and none writes an asset`() {
        val cases =
            listOf(
                Triple(
                    """{"kind":"IMAGE","contentType":"image/gif","sizeBytes":10,"checksum":"${sha256(photo)}"}""",
                    415,
                    "MEDIA_TYPE_UNSUPPORTED",
                ),
                Triple(
                    """{"kind":"IMAGE","contentType":"image/png","sizeBytes":10485761,"checksum":"${sha256(photo)}"}""",
                    413,
                    "MEDIA_QUOTA_EXCEEDED",
                ),
                Triple(
                    """{"kind":"VOICE","contentType":"audio/ogg","sizeBytes":5242881,"checksum":"${sha256(photo)}"}""",
                    413,
                    "MEDIA_QUOTA_EXCEEDED",
                ),
                Triple(
                    """{"kind":"STICKER","contentType":"image/png","sizeBytes":10,"checksum":"${sha256(photo)}"}""",
                    422,
                    "VALIDATION_FAILED",
                ),
                Triple("""{"contentType":"image/png","sizeBytes":10}""", 422, "VALIDATION_FAILED"),
            )
        for ((request, status, code) in cases) {
            val response = intentWith(ada, request)
            response.status shouldBe status
            response.contentAsString shouldContain "\"code\":\"$code\""
        }
        intentWith(ada, """{"kind":"IMAGE","contentType":"image/png","sizeBytes":10}""").contentAsString shouldContain
            "\"field\":\"checksum\""
        jdbc.queryForObject("SELECT count(*) FROM media_assets", Int::class.java) shouldBe 0
    }

    @Test
    fun `a field of the wrong JSON type cannot be read and is 400 MALFORMED_REQUEST, not 422`() {
        val response = intentWith(ada, """{"kind":"IMAGE","contentType":"image/png","sizeBytes":"lots","checksum":"${sha256(photo)}"}""")
        response.status shouldBe 400
        response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
    }

    @Test
    fun `an unauthenticated caller is 401 on every route`() {
        val id = UUID.randomUUID()
        mvc
            .post("/api/v1/media/upload-intent") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andReturn()
            .response.status shouldBe
            401
        mvc
            .get("/api/v1/media/$id")
            .andReturn()
            .response.status shouldBe 401
        mvc
            .delete("/api/v1/media/$id")
            .andReturn()
            .response.status shouldBe 401
        mvc
            .post("/api/v1/media/$id/complete")
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `the asset carries the declared type and size and nothing about the storage, and no URL until it is ready`() {
        val mediaId = uploaded(ada)
        val response = json.readTree(get(ada, mediaId).contentAsString)
        response.propertyNames().asSequence().toList() shouldContainExactly
            listOf("id", "kind", "status", "contentType", "sizeBytes", "url", "width", "height", "durationMs")
        listOf("url", "width", "height", "durationMs").forEach { response[it].isNull shouldBe true }
    }

    @Test
    fun `an asset attached to an entry cannot be deleted, and is left as it was`() {
        val mediaId = uploaded(ada)
        val entry = UUID.randomUUID()
        jdbc.update("UPDATE media_assets SET status = 'READY', attached_entry_id = ? WHERE id = ?::uuid", entry, mediaId)

        val refused = delete(ada, mediaId)

        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"MEDIA_ATTACHED\""
        jdbc.queryForMap("SELECT status, attached_entry_id FROM media_assets WHERE id = ?::uuid", mediaId) shouldBe
            mapOf("status" to "READY", "attached_entry_id" to entry)
    }

    private fun uploaded(owner: UUID): String {
        val body = json.readTree(intent(owner, photo).contentAsString)
        put(body, photo) shouldBe 200
        return body["mediaId"].asString()
    }

    private fun intent(
        owner: UUID,
        bytes: ByteArray,
    ): MockHttpServletResponse =
        intentWith(owner, """{"kind":"IMAGE","contentType":"image/jpeg","sizeBytes":${bytes.size},"checksum":"${sha256(bytes)}"}""")

    private fun intentWith(
        owner: UUID,
        body: String,
    ): MockHttpServletResponse =
        mvc
            .post("/api/v1/media/upload-intent") {
                header(HttpHeaders.AUTHORIZATION, bearer(owner))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun complete(
        caller: UUID,
        mediaId: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mvc
            .post("/api/v1/media/$mediaId/complete") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, key)
            }.andReturn()
            .response

    private fun get(
        caller: UUID,
        mediaId: String,
    ): MockHttpServletResponse =
        mvc.get("/api/v1/media/$mediaId") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    private fun delete(
        caller: UUID,
        mediaId: String,
    ): MockHttpServletResponse =
        mvc.delete("/api/v1/media/$mediaId") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    /** The PUT a phone makes: to the handed-back URL, with the handed-back headers but the length, which the JDK sets. */
    private fun put(
        intent: tools.jackson.databind.JsonNode,
        bytes: ByteArray,
    ): Int {
        val request = HttpRequest.newBuilder(URI.create(intent["uploadUrl"].asString())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        intent["requiredHeaders"].properties().forEach { (name, value) ->
            if (!name.equals("content-length", ignoreCase = true)) request.header(name, value.asString())
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun sha256(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}
