package com.moyi.admin.infra

import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * V69's `account_deletion_request` and `account_deletion_cancel`, the operator's half of deletion asked for by email (ADR-0058,
 * amended 2026-11-16), against a real Postgres through the full Flyway chain. The request it opens
 * must be the one the app opens: one pending row in `deletion_requests`, due exactly
 * `DeletionRequest.CANCELLATION_WINDOW` (14 days) after it was made, so the job and the app's cancel
 * read it as their own. Audit rows cannot be cleaned up, so each test uses an account of its own.
 */
@SpringBootTest
internal class V69MigrationTest(
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `opens one pending request due exactly 14 days later, and audits it by id with the note`() {
        val (id, email) = newUser()

        val row = request(email.uppercase(), "daniel", "confirmed by reply from the account address")

        row["outcome"] shouldBe "REQUESTED"
        row["user_id"] shouldBe id
        row["email"] shouldBe email
        row["status"] shouldBe "ACTIVE"
        val requestedAt = (row["requested_at"] as java.sql.Timestamp).toInstant()
        val executeAfter = (row["execute_after"] as java.sql.Timestamp).toInstant()
        Duration.between(requestedAt, executeAfter) shouldBe Duration.ofDays(14)

        val stored =
            jdbc.queryForMap(
                "SELECT id, requested_at, execute_after, cancelled_at, executed_at FROM deletion_requests WHERE user_id = ?",
                id,
            )
        (stored["requested_at"] as java.sql.Timestamp).toInstant() shouldBe requestedAt
        (stored["execute_after"] as java.sql.Timestamp).toInstant() shouldBe executeAfter
        stored["cancelled_at"] shouldBe null
        stored["executed_at"] shouldBe null

        val audit =
            jdbc.queryForMap(
                "SELECT action, outcome, actor_type, actor_id, target_type, justification, metadata::text AS metadata " +
                    "FROM audit_log WHERE target_id = ?",
                id.toString(),
            )
        audit["action"] shouldBe "ACCOUNT_DELETION_REQUESTED"
        audit["outcome"] shouldBe "SUCCEEDED"
        audit["actor_type"] shouldBe "OPERATOR"
        audit["actor_id"] shouldBe null
        audit["target_type"] shouldBe "USER"
        audit["justification"] shouldBe "confirmed by reply from the account address"
        val metadata = audit["metadata"] as String
        metadata shouldContain "\"operator\": \"daniel\""
        metadata shouldContain stored["id"].toString()
        metadata shouldNotContain email
    }

    @Test
    fun `a second call changes nothing and returns the pending request's dates`() {
        val (id, email) = newUser()
        val first = request(email, "daniel", "first")

        val second = request(email, "daniel", "second")

        second["outcome"] shouldBe "ALREADY_PENDING"
        second["user_id"] shouldBe id
        second["requested_at"] shouldBe first["requested_at"]
        second["execute_after"] shouldBe first["execute_after"]
        count("SELECT count(*) FROM deletion_requests WHERE user_id = ?", id) shouldBe 1
        count("SELECT count(*) FROM audit_log WHERE target_id = ?", id.toString()) shouldBe 1
    }

    @Test
    fun `a request the person cancelled in the app does not stop a new one`() {
        val (id, email) = newUser()
        request(email, "daniel", "first")
        jdbc.update("UPDATE deletion_requests SET cancelled_at = now() WHERE user_id = ?", id)

        request(email, "daniel", "asked again")["outcome"] shouldBe "REQUESTED"

        count("SELECT count(*) FROM deletion_requests WHERE user_id = ? AND cancelled_at IS NULL", id) shouldBe 1
    }

    @Test
    fun `an unknown address and a deleted account are NO_ACCOUNT, and nothing is written`() {
        val before = count("SELECT count(*) FROM deletion_requests")

        request("nobody-${UUID.randomUUID()}@example.com", "daniel", "n")["outcome"] shouldBe "NO_ACCOUNT"

        val (id, email) = newUser(status = "DELETED")
        request(email, "daniel", "n")["outcome"] shouldBe "NO_ACCOUNT"

        count("SELECT count(*) FROM deletion_requests") shouldBe before
        count("SELECT count(*) FROM audit_log WHERE target_id = ?", id.toString()) shouldBe 0
    }

    @Test
    fun `a suspended account can still be deleted, and says so`() {
        val (_, email) = newUser(status = "SUSPENDED")

        val row = request(email, "daniel", "n")

        row["outcome"] shouldBe "REQUESTED"
        row["status"] shouldBe "SUSPENDED"
    }

    @Test
    fun `an address never confirmed is UNVERIFIED, and nothing is written`() {
        val (id, email) = newUser(status = "PENDING_VERIFICATION", verified = false)

        val row = request(email, "daniel", "n")

        row["outcome"] shouldBe "UNVERIFIED"
        row["email"] shouldBe email
        row["status"] shouldBe "PENDING_VERIFICATION"
        count("SELECT count(*) FROM deletion_requests WHERE user_id = ?", id) shouldBe 0
        count("SELECT count(*) FROM audit_log WHERE target_id = ?", id.toString()) shouldBe 0
    }

    @Test
    fun `cancel calls the pending request off, audited, and a new request can follow`() {
        val (id, email) = newUser(status = "SUSPENDED")
        val opened = request(email, "daniel", "asked by email")

        val row = cancel(email.uppercase(), "daniel", "changed their mind, replied from the account address")

        row["outcome"] shouldBe "CANCELLED"
        row["user_id"] shouldBe id
        row["email"] shouldBe email
        row["requested_at"] shouldBe opened["requested_at"]
        count("SELECT count(*) FROM deletion_requests WHERE user_id = ? AND cancelled_at IS NOT NULL", id) shouldBe 1
        val audit =
            jdbc.queryForMap(
                "SELECT actor_type, actor_id, justification, metadata::text AS metadata FROM audit_log " +
                    "WHERE target_id = ? AND action = 'ACCOUNT_DELETION_CANCELLED'",
                id.toString(),
            )
        audit["actor_type"] shouldBe "OPERATOR"
        audit["actor_id"] shouldBe null
        audit["justification"] shouldBe "changed their mind, replied from the account address"
        (audit["metadata"] as String) shouldNotContain email

        cancel(email, "daniel", "again")["outcome"] shouldBe "NOTHING_PENDING"
        request(email, "daniel", "asked once more")["outcome"] shouldBe "REQUESTED"
    }

    @Test
    fun `cancel after the window has closed is NOTHING_PENDING, as the app's 404, and writes nothing`() {
        val (id, email) = newUser()
        request(email, "daniel", "n")
        jdbc.update(
            "UPDATE deletion_requests SET requested_at = now() - interval '337 hours', " +
                "execute_after = now() - interval '1 hour' WHERE user_id = ?",
            id,
        )

        cancel(email, "daniel", "too late")["outcome"] shouldBe "NOTHING_PENDING"

        count("SELECT count(*) FROM deletion_requests WHERE user_id = ? AND cancelled_at IS NULL", id) shouldBe 1
        count("SELECT count(*) FROM audit_log WHERE target_id = ? AND action = 'ACCOUNT_DELETION_CANCELLED'", id.toString()) shouldBe 0
    }

    @Test
    fun `cancel for an unknown address is NO_ACCOUNT, and checks the note and operator`() {
        cancel("nobody-${UUID.randomUUID()}@example.com", "daniel", "n")["outcome"] shouldBe "NO_ACCOUNT"
        val (_, email) = newUser()
        shouldThrow<Exception> { cancel(email, "daniel", " ") }.message!! shouldContain "a note"
        shouldThrow<Exception> { cancel(email, "dan iel", "n") }.message!! shouldContain "an operator"
    }

    @Test
    fun `refuses a missing or overlong note and a missing or malformed operator, writing nothing`() {
        val (id, email) = newUser()

        listOf(
            Triple("daniel", "", "a note"),
            Triple("daniel", "   ", "a note"),
            Triple("daniel", "x".repeat(401), "a note"),
            Triple("", "n", "an operator"),
            Triple("dan iel", "n", "an operator"),
        ).forEach { (operator, note, message) ->
            shouldThrow<Exception> { request(email, operator, note) }.message!! shouldContain message
        }

        count("SELECT count(*) FROM deletion_requests WHERE user_id = ?", id) shouldBe 0
    }

    private fun request(
        email: String,
        operator: String,
        note: String,
    ): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT outcome, user_id, email, status, requested_at, execute_after " +
                "FROM account_deletion_request(CAST(? AS citext), ?, ?)",
            email,
            operator,
            note,
        )

    private fun cancel(
        email: String,
        operator: String,
        note: String,
    ): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT outcome, user_id, email, status, requested_at, execute_after " +
                "FROM account_deletion_cancel(CAST(? AS citext), ?, ?)",
            email,
            operator,
            note,
        )

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    private fun newUser(
        status: String = "ACTIVE",
        verified: Boolean = true,
    ): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val email = "del-$id@example.com"
        jdbc.update(
            "INSERT INTO users (id, email, display_name, status, email_verified_at) " +
                "VALUES (?, ?, 'Ada', ?, CASE WHEN ? THEN now() END)",
            id,
            email,
            status,
            verified,
        )
        return id to email
    }

    companion object {
        /** V69's actions, which V61's, V64's, V66's and V67's tests leave out of "every action of the enum". */
        val V69_ACTIONS = listOf("ACCOUNT_DELETION_REQUESTED", "ACCOUNT_DELETION_CANCELLED")
    }
}
