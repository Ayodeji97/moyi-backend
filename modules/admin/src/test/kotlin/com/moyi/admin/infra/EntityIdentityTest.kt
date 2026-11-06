package com.moyi.admin.infra

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AdminStatus
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Entities are equal by id, and their text form (which reaches logs) carries no credential. */
internal class EntityIdentityTest {
    private val now = Instant.parse("2026-11-06T10:00:00Z")

    @Test
    fun `an admin account is equal to another with the same id and prints no secret`() {
        val id = UUID.randomUUID()

        val a = account(id, "a@example.com")
        val b = account(id, "b@example.com")

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        (a == account(UUID.randomUUID(), "a@example.com")) shouldBe false
        a.toString() shouldNotContain "a@example.com"
        a.toString() shouldNotContain "secret-hash"
        a.toString() shouldNotContain "secret-totp"
    }

    @Test
    fun `an audit row is equal to another with the same id`() {
        val id = UUID.randomUUID()

        audit(id) shouldBe audit(id)
        audit(id).hashCode() shouldBe audit(id).hashCode()
        (audit(id) == audit(UUID.randomUUID())) shouldBe false
        audit(id).toString() shouldNotContain "ADMIN_ACCOUNT"
    }

    private fun account(
        id: UUID,
        email: String,
    ) = AdminAccountEntity(
        id,
        email,
        AdminRole.ADMIN,
        "secret-hash",
        now,
        "secret-totp",
        3,
        AdminStatus.ACTIVE,
        0,
        null,
        now,
        now,
        null,
    )

    private fun audit(id: UUID) =
        AuditLogEntity(
            id,
            now,
            "ADMIN",
            null,
            AuditAction.ADMIN_SIGN_IN,
            "ADMIN_ACCOUNT",
            "x",
            null,
            AuditOutcome.SUCCEEDED,
            "{}",
            null,
        )
}
