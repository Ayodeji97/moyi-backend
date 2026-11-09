package com.moyi.export.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.export.api.DataExportJobs
import com.moyi.export.infra.ExportTestApplication
import com.moyi.export.infra.FakeStorage
import com.moyi.export.infra.People
import com.moyi.export.infra.RecordingMail
import com.moyi.identity.api.ExportedAccount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The three routes (FR-009, FR-094, ADR-0059 decisions 1 to 4): a `202` that is the same export
 * while one is being prepared, one `404` for an export that is someone else's or nobody's, the
 * URL while it is READY and never after its 24 hours, and the job that makes it so.
 */
@SpringBootTest(classes = [ExportTestApplication::class])
@AutoConfigureMockMvc
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class DataExportEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val jobs: DataExportJobs,
    @Autowired private val clock: MutableClock,
    @Autowired private val people: People,
    @Autowired private val storage: FakeStorage,
    @Autowired private val mail: RecordingMail,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        clear()
        clock.set(ExportTestApplication.START)
        listOf(ada, bea).forEach { people.accounts[it] = account(it) }
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE data_exports")
        people.clear()
        storage.clear()
        mail.sent.clear()
    }

    @Test
    fun `a request is 202 PREPARING, and asking again while it is prepared is the same export`() {
        val first = post(ada)

        first.status shouldBe 202
        val body = body(first)
        body["status"].asString() shouldBe "PREPARING"
        Instant.parse(body["requestedAt"].asString()) shouldBe ExportTestApplication.START
        body["downloadUrl"].isNull shouldBe true
        body["expiresAt"].isNull shouldBe true
        clock.advance(Duration.ofSeconds(5))
        body(post(ada))["id"] shouldBe body["id"]
        jdbc.queryForObject("SELECT count(*) FROM data_exports WHERE user_id = ?", Int::class.java, ada) shouldBe 1
    }

    @Test
    fun `someone else's export and one that does not exist are the same 404, and so is the latest when there is none`() {
        val adas = body(post(ada))["id"].asString()

        val other = get(bea, "/$adas")
        val missing = get(bea, "/${UUID.randomUUID()}")

        other.status shouldBe 404
        missing.status shouldBe 404
        strip(other.contentAsString) shouldBe strip(missing.contentAsString)
        get(bea, "").status shouldBe 404
        get(ada, "/$adas").status shouldBe 200
    }

    @Test
    fun `without a bearer every route is 401, and nothing is asked for`() {
        mvc
            .post("/api/v1/me/export")
            .andReturn()
            .response.status shouldBe 401
        mvc
            .get("/api/v1/me/export")
            .andReturn()
            .response.status shouldBe 401
        mvc
            .get("/api/v1/me/export/${UUID.randomUUID()}")
            .andReturn()
            .response.status shouldBe 401
        jdbc.queryForObject("SELECT count(*) FROM data_exports", Int::class.java) shouldBe 0
    }

    @Test
    fun `built, it is READY with a URL good for exactly 24 hours, and the person is emailed without the link`() {
        val id = body(post(ada))["id"].asString()
        clock.advance(Duration.ofMinutes(1))

        jobs.prepareDue(5) shouldBe 1

        val ready = body(get(ada, "/$id"))
        ready["status"].asString() shouldBe "READY"
        val readyAt = Instant.parse(ready["readyAt"].asString())
        Instant.parse(ready["expiresAt"].asString()) shouldBe readyAt.plus(Duration.ofHours(24))
        ready["downloadUrl"].asString() shouldContain "expires=86400"
        ready["downloadUrl"].asString() shouldContain "signed=${readyAt.epochSecond}"
        ready["sizeBytes"].asLong() shouldBe
            storage.archives.values
                .single()
                .size
                .toLong()
        body(get(ada, ""))["id"].asString() shouldBe id
        val (to, message) = mail.sent.single()
        to shouldBe ada
        message.idempotencyKey shouldBe "export-ready-$id"
        message.text shouldNotContain "storage.test"
        message.text shouldNotContain "http"
        message.html!! shouldNotContain "http"
    }

    @Test
    fun `at its 24th hour it is EXPIRED with no URL, before the job runs, and the job deletes the archive`() {
        val id = body(post(ada))["id"].asString()
        jobs.prepareDue(5)
        val key = storage.archives.keys.single()
        clock.advance(Duration.ofHours(24))

        val expired = body(get(ada, "/$id"))

        expired["status"].asString() shouldBe "EXPIRED"
        expired["downloadUrl"].isNull shouldBe true
        expired["expiresAt"].isNull shouldBe true
        storage.archives.keys shouldBe setOf(key)

        jobs.expireDue(50) shouldBe 1

        storage.archives.keys.shouldBeEmpty()
        storage.deleted shouldBe listOf(key)
        jdbc.queryForMap("SELECT status, storage_key FROM data_exports WHERE id = ?::uuid", id) shouldBe
            mapOf("status" to "EXPIRED", "storage_key" to null)
        jobs.expireDue(50) shouldBe 0
    }

    @Test
    fun `a minute before its 24th hour it is still READY, and the job leaves it alone`() {
        body(post(ada))
        jobs.prepareDue(5)
        clock.advance(Duration.ofHours(24).minusMinutes(1))

        jobs.expireDue(50) shouldBe 0

        body(get(ada, ""))["status"].asString() shouldBe "READY"
    }

    @Test
    fun `a build that fails is tried again, given up as FAILED on the third, and a new one can then be asked for`() {
        val id = body(post(ada))["id"].asString()
        storage.failing = true

        repeat(3) {
            jobs.prepareDue(5) shouldBe 0
        }

        body(get(ada, "/$id"))["status"].asString() shouldBe "FAILED"
        jobs.prepareDue(5) shouldBe 0
        mail.sent.shouldBeEmpty()
        storage.failing = false
        val again = body(post(ada))
        (again["id"].asString() == id) shouldBe false
        again["status"].asString() shouldBe "PREPARING"
    }

    @Test
    fun `after it is READY, asking again is a new export, and the latest is the new one`() {
        val first = body(post(ada))["id"].asString()
        jobs.prepareDue(5)
        clock.advance(Duration.ofMinutes(1))

        val second = body(post(ada))["id"].asString()

        (second == first) shouldBe false
        body(get(ada, ""))["id"].asString() shouldBe second
        body(get(ada, "/$first"))["status"].asString() shouldBe "READY"
    }

    @Test
    fun `an export whose row went while it was built has its archive deleted again, and nobody is emailed`() {
        body(post(ada))
        people.duringBuild = { jdbc.update("DELETE FROM data_exports WHERE user_id = ?", ada) }

        jobs.prepareDue(5) shouldBe 0

        storage.archives.keys.shouldBeEmpty()
        storage.deleted shouldHaveSize 1
        mail.sent.shouldBeEmpty()
    }

    private fun account(id: UUID) =
        ExportedAccount(
            id = id,
            email = "$id@example.com",
            emailVerifiedAt = ExportTestApplication.START,
            displayName = "Someone",
            locale = "en",
            status = "ACTIVE",
            createdAt = ExportTestApplication.START,
            updatedAt = null,
            consents = emptyList(),
        )

    private fun post(user: UUID): MockHttpServletResponse =
        mvc.post("/api/v1/me/export") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }.andReturn().response

    private fun get(
        user: UUID,
        path: String,
    ): MockHttpServletResponse = mvc.get("/api/v1/me/export$path") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }.andReturn().response

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    /** The two 404s differ only in the path they echo and their trace. */
    private fun strip(body: String): String = body.replace(Regex(""""(instance|traceId)":"[^"]*""""), "")
}
