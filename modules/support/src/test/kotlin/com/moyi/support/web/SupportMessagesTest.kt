package com.moyi.support.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.support.infra.RecordingAccountMail
import com.moyi.support.infra.SupportTestApplication
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /support/messages` (FR-102, ADR-0057): a ticket, an acknowledgement that never repeats it,
 * a `422` naming each field, a `401` without a bearer, and an optional key a retry is not filed twice under.
 */
@SpringBootTest(classes = [SupportTestApplication::class])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
internal class SupportMessagesTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val mail: RecordingAccountMail,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun clear() {
        mail.sent.clear()
        jdbc.execute("TRUNCATE TABLE support_ticket_log, support_tickets, idempotency_keys")
    }

    @Test
    fun `a message is a ticket, answered with its id and time, and acknowledged without a word of it`(output: CapturedOutput) {
        val ada = UUID.randomUUID()

        val sent = send(ada, """{"subject":"$SUBJECT","body":"$BODY"}""")

        sent.status shouldBe 201
        val body = body(sent)
        body.propertyNames().asSequence().toList() shouldBe listOf("id", "createdAt")
        val id = UUID.fromString(body["id"].asString())
        Instant.parse(body["createdAt"].asString())
        jdbc.queryForMap("SELECT user_id, subject, body, status FROM support_tickets WHERE id = ?", id) shouldBe
            mapOf("user_id" to ada, "subject" to SUBJECT, "body" to BODY, "status" to "OPEN")

        await().atMost(WAIT).until { mail.sent.isNotEmpty() }
        val (to, message) = mail.sent.single()
        to shouldBe ada
        message.idempotencyKey shouldBe "support-ack-$id"
        message.text shouldNotContain WATCHED
        message.html!! shouldNotContain WATCHED
        message.subject shouldNotContain WATCHED
        message.text.contains(id.toString()) shouldBe true
        output.all shouldNotContain WATCHED
    }

    @Test
    fun `each field is refused by name, missing, blank or too long, and the refusal never repeats it`(output: CapturedOutput) {
        val ada = UUID.randomUUID()
        val long = WATCHED.repeat(5001 / WATCHED.length + 1)
        mapOf(
            """{"body":"$BODY"}""" to "subject",
            """{"subject":"   ","body":"$BODY"}""" to "subject",
            """{"subject":"${"s".repeat(121)}","body":"$BODY"}""" to "subject",
            """{"subject":"$SUBJECT"}""" to "body",
            """{"subject":"$SUBJECT","body":""}""" to "body",
            """{"subject":"$SUBJECT","body":"$long"}""" to "body",
        ).forEach { (request, field) ->
            val refused = send(ada, request)
            refused.status shouldBe 422
            body(refused)["code"].asString() shouldBe "VALIDATION_FAILED"
            body(refused)["errors"].toList().map { it["field"].asString() } shouldBe listOf(field)
            refused.contentAsString shouldNotContain WATCHED
        }
        jdbc.queryForObject("SELECT count(*) FROM support_tickets", Long::class.java) shouldBe 0
        mail.sent shouldHaveSize 0
        output.all shouldNotContain WATCHED
    }

    @Test
    fun `without a bearer it is 401, and nothing is filed`() {
        mvc
            .post("/api/v1/support/messages") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"subject":"$SUBJECT","body":"$BODY"}"""
            }.andReturn()
            .response.status shouldBe 401
        jdbc.queryForObject("SELECT count(*) FROM support_tickets", Long::class.java) shouldBe 0
    }

    @Test
    fun `a retry under the same key is the same ticket, filed and acknowledged once`() {
        val ada = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        val request = """{"subject":"$SUBJECT","body":"$BODY"}"""

        val first = send(ada, request, key)
        val again = send(ada, request, key)

        first.status shouldBe 201
        again.status shouldBe 201
        again.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        first.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe null
        body(again)["id"] shouldBe body(first)["id"]
        body(again)["createdAt"] shouldBe body(first)["createdAt"]
        jdbc.queryForObject("SELECT count(*) FROM support_tickets", Long::class.java) shouldBe 1
        await().atMost(WAIT).until { mail.sent.isNotEmpty() }
        // As identity's resend test: a second email would arrive on another thread, so a short settle, then none.
        Thread.sleep(SETTLE_MILLIS)
        mail.sent shouldHaveSize 1
    }

    @Test
    fun `the same key with different words is refused, and without a key two messages are two tickets`() {
        val ada = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        send(ada, """{"subject":"$SUBJECT","body":"$BODY"}""", key).status shouldBe 201
        send(ada, """{"subject":"$SUBJECT","body":"something else"}""", key).status shouldBe 422

        send(ada, """{"subject":"$SUBJECT","body":"$BODY"}""").status shouldBe 201
        send(ada, """{"subject":"$SUBJECT","body":"$BODY"}""").status shouldBe 201
        jdbc.queryForObject("SELECT count(*) FROM support_tickets", Long::class.java) shouldBe 3
    }

    @Test
    fun `the operator's close empties the words and keeps the record, and each look is logged`() {
        val ada = UUID.randomUUID()
        val id = UUID.fromString(body(send(ada, """{"subject":"$SUBJECT","body":"$BODY"}"""))["id"].asString())

        jdbc.queryForList("SELECT ticket_id FROM support_ticket_list('op')", UUID::class.java) shouldBe listOf(id)
        jdbc.queryForMap("SELECT subject, body FROM support_ticket_read(?, 'op')", id) shouldBe
            mapOf("subject" to SUBJECT, "body" to BODY)
        jdbc.queryForObject("SELECT support_ticket_close(?, 'op', 'answered by email')", String::class.java, id) shouldBe "CLOSED"
        jdbc.queryForObject("SELECT support_ticket_close(?, 'op', 'again')", String::class.java, id) shouldBe "NOT_OPEN"

        jdbc.queryForMap("SELECT subject, body, status FROM support_tickets WHERE id = ?", id) shouldBe
            mapOf("subject" to null, "body" to null, "status" to "CLOSED")
        jdbc.queryForList("SELECT action FROM support_ticket_log ORDER BY at, action", String::class.java) shouldBe
            listOf("LISTED", "READ", "CLOSED")
    }

    private fun send(
        user: UUID,
        request: String,
        key: String? = null,
    ): MockHttpServletResponse =
        mvc
            .post("/api/v1/support/messages") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                key?.let { header(IdempotencyInterceptor.HEADER, it) }
                contentType = MediaType.APPLICATION_JSON
                content = request
            }.andReturn()
            .response

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private companion object {
        /** A word nobody else writes, in both fields: found anywhere but the table, it leaked. */
        const val WATCHED = "Quixotically"
        const val SUBJECT = "$WATCHED locked out"
        const val BODY = "My partner and I $WATCHED cannot see today's entries."
        const val SETTLE_MILLIS = 500L
        val WAIT: Duration = Duration.ofSeconds(5)
    }
}
