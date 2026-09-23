package com.moyi.identity.infra.database

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_tokens")
internal class RefreshTokenEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,
    @Column(name = "family_id", nullable = false, updatable = false)
    var familyId: UUID,
    @Column(name = "token_hash", nullable = false, updatable = false)
    var tokenHash: String,
    @Column(name = "issued_at", nullable = false, updatable = false)
    var issuedAt: Instant,
    @Column(name = "expires_at", nullable = false, updatable = false)
    var expiresAt: Instant,
    @Column(name = "rotated_at")
    var rotatedAt: Instant?,
    @Column(name = "revoked_at")
    var revokedAt: Instant?,
    @Column(name = "replaced_by")
    var replacedBy: UUID?,
    @Column(name = "device_info", length = 200)
    var deviceInfo: String?,
) : Persistable<UUID> {
    @Transient
    private var new: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = new

    @PostPersist
    @PostLoad
    private fun markNotNew() {
        new = false
    }

    override fun equals(other: Any?): Boolean = this === other || (other is RefreshTokenEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "RefreshTokenEntity(id=$id, familyId=$familyId, expiresAt=$expiresAt, rotated=${rotatedAt != null}, revoked=${revokedAt != null})"
}
