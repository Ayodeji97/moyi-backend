package com.moyi.admin.infra

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.service.AuditTrail
import com.moyi.common.security.ClientAddress
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
internal class AuditTrailTest(
    @Autowired private val trail: AuditTrail,
    @Autowired private val log: AuditLogRepository,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `record writes an ADMIN row with the hashed address and the metadata`() {
        val actor = UUID.randomUUID()
        val target = UUID.randomUUID().toString()

        transactions.executeWithoutResult {
            trail.record(
                action = AuditAction.USER_LOOKED_UP,
                outcome = AuditOutcome.SUCCEEDED,
                actorId = actor,
                targetType = "USER",
                targetId = target,
                metadata = mapOf("matched" to true),
                clientAddress = ClientAddress.parse("203.0.113.7"),
            )
        }

        val row =
            jdbc.queryForMap(
                "SELECT actor_type, actor_id, outcome, metadata ->> 'matched' AS matched, length(ip_hash) AS len " +
                    "FROM audit_log WHERE target_id = ?",
                target,
            )
        row["actor_type"] shouldBe "ADMIN"
        row["actor_id"] shouldBe actor
        row["outcome"] shouldBe "SUCCEEDED"
        row["matched"] shouldBe "true"
        row["len"] shouldBe 32
    }

    @Test
    fun `an audit row reads back with every column`() {
        val id = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val at = Instant.parse("2026-11-06T10:00:00Z")
        log.save(
            AuditLogEntity(
                id,
                at,
                "ADMIN",
                actor,
                AuditAction.USER_LOOKED_UP,
                "USER",
                "u-1",
                "ticket 4",
                AuditOutcome.REFUSED,
                """{"k": 1}""",
                byteArrayOf(1, 2, 3),
            ),
        )

        val row = log.findById(id)!!
        row.occurredAt shouldBe at
        row.actorType shouldBe "ADMIN"
        row.actorId shouldBe actor
        row.action shouldBe AuditAction.USER_LOOKED_UP
        row.targetType shouldBe "USER"
        row.targetId shouldBe "u-1"
        row.justification shouldBe "ticket 4"
        row.outcome shouldBe AuditOutcome.REFUSED
        row.ipHash!!.toList() shouldBe listOf<Byte>(1, 2, 3)
        row.id shouldBe id
    }

    @Test
    fun `record without an address stores no ip hash`() {
        val target = UUID.randomUUID().toString()

        transactions.executeWithoutResult {
            trail.record(AuditAction.ADMIN_SIGN_IN, AuditOutcome.REFUSED, null, "ADMIN_ACCOUNT", target, clientAddress = null)
        }

        jdbc.queryForObject("SELECT ip_hash IS NULL FROM audit_log WHERE target_id = ?", Boolean::class.java, target) shouldBe true
    }

    @Test
    fun `record refuses to run outside a transaction`() {
        shouldThrow<IllegalTransactionStateException> {
            trail.record(AuditAction.ADMIN_SIGN_OUT, AuditOutcome.SUCCEEDED, null, null, null, clientAddress = null)
        }
    }

    @Test
    fun `an audit row rolls back with the transaction that wrote it`() {
        val target = UUID.randomUUID().toString()

        shouldThrow<IllegalStateException> {
            transactions.executeWithoutResult {
                trail.record(AuditAction.ADMIN_SIGN_OUT, AuditOutcome.SUCCEEDED, null, "ADMIN_ACCOUNT", target, clientAddress = null)
                error("the audited act failed")
            }
        }

        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id = ?", Int::class.java, target) shouldBe 0
    }
}
