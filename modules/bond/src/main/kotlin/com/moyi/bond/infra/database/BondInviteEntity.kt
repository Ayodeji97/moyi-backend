package com.moyi.bond.infra.database

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

/**
 * The `bond_invites` row.
 *
 * [toString] omits the code. A live invite code is a credential — whoever
 * holds it joins the bond (T-06) — and a `toString` is exactly how a
 * credential reaches a log without anyone deciding that it should.
 */
@Entity
@Table(name = "bond_invites")
internal class BondInviteEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var bondId: UUID,
    @Column(nullable = false, updatable = false)
    var code: String,
    @Column(nullable = false, updatable = false)
    var createdByMemberId: UUID,
    @Column(nullable = false, updatable = false)
    var expiresAt: Instant,
    var usedAt: Instant?,
    var usedByUserId: UUID?,
    var revokedAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is BondInviteEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "BondInviteEntity(id=$id, bondId=$bondId)"
}
