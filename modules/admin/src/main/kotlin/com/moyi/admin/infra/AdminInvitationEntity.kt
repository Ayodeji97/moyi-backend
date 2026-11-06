package com.moyi.admin.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An `admin_invitations` row (spec §5): only the SHA-256 of the one-time token, never the
 * token. Rows are written by the operator's `admin_invite` function; the application only
 * reads them and marks one consumed, so this entity is never persisted new from Kotlin.
 */
@Entity
@Table(name = "admin_invitations")
internal class AdminInvitationEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,
    @Column(nullable = false, updatable = false)
    val adminId: UUID,
    @Column(nullable = false, updatable = false)
    val tokenHash: ByteArray,
    @Column(nullable = false, updatable = false)
    val expiresAt: Instant,
    var consumedAt: Instant?,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is AdminInvitationEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    // Never the hash: it is what the token is checked against.
    override fun toString(): String = "AdminInvitationEntity(id=$id, adminId=$adminId)"
}
