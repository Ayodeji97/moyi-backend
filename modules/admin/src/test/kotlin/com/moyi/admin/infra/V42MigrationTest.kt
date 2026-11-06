package com.moyi.admin.infra

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AdminStatus
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * V42 against a real Postgres, through the full Flyway chain: the operator's
 * functions, the append-only trigger, and the TOTP compare-and-set. Audit rows
 * cannot be cleaned up (that is the point of them), so each test uses an email
 * of its own.
 */
@SpringBootTest
internal class V42MigrationTest(
    @Autowired private val accounts: AdminAccountRepository,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `admin_invite returns a 43 character token, stores only its hash and audits without it`() {
        val email = newEmail()

        val token = invite(email, "SUPPORT")

        token.length shouldBe 43
        Regex("[A-Za-z0-9_-]{43}").matches(token) shouldBe true
        val account = jdbc.queryForMap("SELECT id, role, status FROM admin_accounts WHERE email = ?", email)
        account["status"] shouldBe "INVITED"
        account["role"] shouldBe "SUPPORT"

        val invitation =
            jdbc.queryForMap(
                "SELECT encode(token_hash, 'hex') AS hash, expires_at, consumed_at FROM admin_invitations WHERE admin_id = ?",
                account["id"],
            )
        val expected = MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(token))
        invitation["hash"] shouldBe expected.joinToString("") { "%02x".format(it) }
        invitation["consumed_at"] shouldBe null
        val expiresAt = (invitation["expires_at"] as java.sql.Timestamp).toInstant()
        val untilExpiry = Duration.between(Instant.now(), expiresAt)
        (untilExpiry > Duration.ofHours(24).minusMinutes(1) && untilExpiry < Duration.ofHours(24).plusMinutes(1)) shouldBe true

        val audit =
            jdbc.queryForMap(
                "SELECT action, outcome, actor_type, actor_id, target_type, metadata::text AS metadata " +
                    "FROM audit_log WHERE target_id = ?",
                account["id"].toString(),
            )
        audit["action"] shouldBe "ADMIN_INVITED"
        audit["outcome"] shouldBe "SUCCEEDED"
        audit["actor_type"] shouldBe "OPERATOR"
        audit["actor_id"] shouldBe null
        (audit["metadata"] as String) shouldNotContain token
        (audit["metadata"] as String) shouldNotContain email
    }

    @Test
    fun `re-inviting an active account resets its credentials and moves credentials_changed_at forward`() {
        val email = newEmail()
        invite(email, "SUPPORT")
        jdbc.update(
            "UPDATE admin_accounts SET status = 'ACTIVE', password_hash = 'h', totp_secret_enc = 's', totp_last_step = 9, " +
                "failed_attempts = 4, locked_until = now(), credentials_changed_at = now() - interval '1 day' WHERE email = ?",
            email,
        )
        val before = changedAt(email)

        invite(email, "MODERATOR")

        val row =
            jdbc.queryForMap(
                "SELECT status, role, password_hash, totp_secret_enc, totp_last_step, failed_attempts, locked_until " +
                    "FROM admin_accounts WHERE email = ?",
                email,
            )
        row["status"] shouldBe "INVITED"
        row["role"] shouldBe "MODERATOR"
        row["password_hash"] shouldBe null
        row["totp_secret_enc"] shouldBe null
        row["totp_last_step"] shouldBe null
        row["failed_attempts"] shouldBe 0
        row["locked_until"] shouldBe null
        (changedAt(email) > before) shouldBe true
        jdbc.queryForObject(
            "SELECT count(*) FROM admin_invitations i JOIN admin_accounts a ON a.id = i.admin_id WHERE a.email = ?",
            Int::class.java,
            email,
        ) shouldBe 1
    }

    @Test
    fun `admin_invite refuses a role outside the check`() {
        val email = newEmail()

        shouldThrow<Exception> { invite(email, "ROOT") }

        jdbc.queryForObject("SELECT count(*) FROM admin_accounts WHERE email = ?", Int::class.java, email) shouldBe 0
    }

    @Test
    fun `admin_disable disables, ends sessions and audits, and an unknown email raises`() {
        val email = newEmail()
        invite(email, "ADMIN")
        jdbc.update(
            "UPDATE admin_accounts SET status = 'ACTIVE', credentials_changed_at = now() - interval '1 day' WHERE email = ?",
            email,
        )
        val before = changedAt(email)

        jdbc.queryForList("SELECT admin_disable(CAST(? AS citext))", email)

        val row = jdbc.queryForMap("SELECT id, status, disabled_at FROM admin_accounts WHERE email = ?", email)
        row["status"] shouldBe "DISABLED"
        (row["disabled_at"] != null) shouldBe true
        (changedAt(email) > before) shouldBe true
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE target_id = ? AND action = 'ADMIN_DISABLED' " +
                "AND actor_type = 'OPERATOR' AND actor_id IS NULL",
            Int::class.java,
            row["id"].toString(),
        ) shouldBe 1

        shouldThrow<Exception> { jdbc.queryForList("SELECT admin_disable(CAST(? AS citext))", newEmail()) }
            .message!! shouldContain "no such admin"
    }

    @Test
    fun `audit_log refuses update, delete and truncate`() {
        invite(newEmail(), "SUPPORT")

        shouldThrow<Exception> { jdbc.update("UPDATE audit_log SET outcome = 'FAILED'") }
            .message!! shouldContain "audit_log is append-only"
        shouldThrow<Exception> { jdbc.update("DELETE FROM audit_log") }
            .message!! shouldContain "audit_log is append-only"
        shouldThrow<Exception> { jdbc.execute("TRUNCATE audit_log") }
            .message!! shouldContain "audit_log is append-only"
    }

    @Test
    fun `casTotpStep accepts a later step once and refuses a repeat or an earlier one`() {
        val id = UUID.randomUUID()
        val now = Instant.now()
        accounts.save(
            AdminAccountEntity(
                id,
                newEmail(),
                AdminRole.SUPPORT,
                null,
                now,
                null,
                null,
                AdminStatus.ACTIVE,
                0,
                null,
                now,
                now,
                null,
            ),
        )

        inTransaction { accounts.casTotpStep(id, 5) } shouldBe 1
        inTransaction { accounts.casTotpStep(id, 5) } shouldBe 0
        inTransaction { accounts.casTotpStep(id, 4) } shouldBe 0
        inTransaction { accounts.casTotpStep(id, 6) } shouldBe 1
    }

    @Test
    fun `findByEmail is case-insensitive`() {
        val email = newEmail()
        invite(email, "SUPPORT")

        (accounts.findByEmail(email.uppercase()) != null) shouldBe true
    }

    @Test
    fun `an account round-trips every column through the entity`() {
        val id = UUID.randomUUID()
        val email = newEmail()
        val at = Instant.parse("2026-11-06T10:00:00Z")
        accounts.save(
            AdminAccountEntity(id, email, AdminRole.SUPPORT, null, at, null, null, AdminStatus.INVITED, 0, null, at, null, null),
        )

        transactions.executeWithoutResult {
            val row = accounts.findByEmail(email)!!
            row.role = AdminRole.MODERATOR
            row.passwordHash = "hash"
            row.credentialsChangedAt = at.plusSeconds(1)
            row.totpSecretEnc = "enc"
            row.totpLastStep = 7
            row.status = AdminStatus.ACTIVE
            row.failedAttempts = 2
            row.lockedUntil = at.plusSeconds(60)
            row.activatedAt = at.plusSeconds(2)
            row.disabledAt = at.plusSeconds(3)
            row.email = email.uppercase()
        }

        val row = accounts.findById(id).get()
        row.role shouldBe AdminRole.MODERATOR
        row.passwordHash shouldBe "hash"
        row.credentialsChangedAt shouldBe at.plusSeconds(1)
        row.totpSecretEnc shouldBe "enc"
        row.totpLastStep shouldBe 7
        row.status shouldBe AdminStatus.ACTIVE
        row.failedAttempts shouldBe 2
        row.lockedUntil shouldBe at.plusSeconds(60)
        row.createdAt shouldBe at
        row.activatedAt shouldBe at.plusSeconds(2)
        row.disabledAt shouldBe at.plusSeconds(3)
        row.email shouldBe email.uppercase()
    }

    private fun invite(
        email: String,
        role: String,
    ): String = jdbc.queryForObject("SELECT admin_invite(CAST(? AS citext), ?)", String::class.java, email, role)!!

    private fun changedAt(email: String): Instant =
        jdbc
            .queryForObject("SELECT credentials_changed_at FROM admin_accounts WHERE email = ?", OffsetDateTime::class.java, email)!!
            .toInstant()

    private fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }!!

    private fun newEmail() = "op-${UUID.randomUUID()}@example.com"
}
