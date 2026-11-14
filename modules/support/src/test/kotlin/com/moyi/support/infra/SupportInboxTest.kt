package com.moyi.support.infra

import com.moyi.common.testing.IntegrationTest
import com.moyi.support.api.SupportInbox
import com.moyi.support.api.TicketCloseOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

/**
 * The admin console's inbox port (ADR-0067 decisions 12 and 13) over ADR-0057's functions: what each
 * returns, the `support_ticket_log` row each writes under the actor given, and that none runs outside
 * the caller's transaction.
 */
@SpringBootTest(classes = [SupportTestApplication::class])
internal class SupportInboxTest(
    @Autowired private val inbox: SupportInbox,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val actor = "admin:${UUID.randomUUID()}"

    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.update("DELETE FROM support_ticket_log")
        jdbc.update("DELETE FROM support_tickets")
    }

    @Test
    fun `the list is the open tickets oldest first with their lengths, and logs LISTED under the actor`() {
        val older = ticket(subject = "Help", body = "my streak broke", ageMinutes = 10)
        val newer = ticket(subject = "Hi", body = "x", ageMinutes = 1)
        ticket(subject = "gone", body = "gone", ageMinutes = 20).also { close(it) }

        val rows = transactions.execute { inbox.list(actor) }!!

        rows.map { it.ticketId } shouldContainExactly listOf(older, newer)
        rows.first().subjectLength shouldBe 4
        rows.first().bodyLength shouldBe 15
        rows.first().toString() shouldNotContain "streak"
        log() shouldBe listOf(Triple(null, "LISTED", actor))
        inbox.openCount() shouldBe 2
    }

    @Test
    fun `a read returns the words, the reference and where to answer, and logs READ`() {
        val user = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, locale, status, created_at, email_verified_at) " +
                "VALUES (?, ?, 'Ada', 'en', 'ACTIVE', now(), now())",
            user,
            "ada-$user@example.com",
        )
        val id = ticket(subject = "Help", body = "my streak broke", user = user, reference = "MOYI-1")

        val case = transactions.execute { inbox.read(id, actor) }!!

        case.open shouldBe true
        case.subject shouldBe "Help"
        case.body shouldBe "my streak broke"
        case.reference shouldBe "MOYI-1"
        case.replyTo shouldBe "ada-$user@example.com"
        case.replyToConfirmed shouldBe true
        case.toString().let { text -> listOf("Help", "streak", "MOYI-1", "ada-").forEach { text shouldNotContain it } }
        log() shouldBe listOf(Triple(id, "READ", actor))
    }

    @Test
    fun `a read of an account since deleted has no address to answer at`() {
        val id = ticket(subject = "s", body = "b", user = UUID.randomUUID())

        val case = transactions.execute { inbox.read(id, actor) }!!

        case.replyTo shouldBe null
        case.replyToConfirmed shouldBe false
    }

    @Test
    fun `an unknown ticket reads as nothing and logs nothing`() {
        transactions.execute { inbox.read(UUID.randomUUID(), actor) } shouldBe null
        log() shouldBe emptyList()
    }

    @Test
    fun `a close empties the words and logs CLOSED with the note, and a second is NOT_OPEN`() {
        val id = ticket(subject = "Help", body = "words", reference = "R1")

        transactions.execute { inbox.close(id, actor, "answered by email") } shouldBe TicketCloseOutcome.CLOSED
        transactions.execute { inbox.close(id, actor, "again") } shouldBe TicketCloseOutcome.NOT_OPEN

        jdbc.queryForMap("SELECT status, subject, body, reference FROM support_tickets WHERE id = ?", id) shouldBe
            mapOf("status" to "CLOSED", "subject" to null, "body" to null, "reference" to null)
        jdbc.queryForList("SELECT note FROM support_ticket_log WHERE action = 'CLOSED'", String::class.java) shouldBe
            listOf("answered by email")
        val closed = transactions.execute { inbox.read(id, actor) }!!
        closed.open shouldBe false
        closed.body shouldBe null
    }

    @Test
    fun `none of the three runs outside a transaction`() {
        val id = ticket(subject = "s", body = "b")
        shouldThrow<IllegalTransactionStateException> { inbox.list(actor) }
        shouldThrow<IllegalTransactionStateException> { inbox.read(id, actor) }
        shouldThrow<IllegalTransactionStateException> { inbox.close(id, actor, "n") }
        log() shouldBe emptyList()
    }

    private fun ticket(
        subject: String,
        body: String,
        user: UUID = UUID.randomUUID(),
        reference: String? = null,
        ageMinutes: Int = 5,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO support_tickets (id, user_id, subject, body, reference, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 'OPEN', now() - make_interval(mins => ?))",
            id,
            user,
            subject,
            body,
            reference,
            ageMinutes,
        )
        return id
    }

    private fun close(id: UUID) {
        jdbc.update("UPDATE support_tickets SET status = 'CLOSED', closed_at = now(), subject = NULL, body = NULL WHERE id = ?", id)
    }

    private fun log(): List<Triple<UUID?, String, String>> =
        jdbc.query("SELECT ticket_id, action, actor FROM support_ticket_log ORDER BY at, id") { rs, _ ->
            Triple(rs.getObject("ticket_id", UUID::class.java), rs.getString("action"), rs.getString("actor"))
        }
}
