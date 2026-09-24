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
 * The `blocks` row (FR-029). Written by slice B3, read by B2's accept.
 *
 * Every column is `updatable = false`: a block is a fact about a moment, and
 * unblocking — if it is ever a product decision — is deleting the row, not
 * editing it. `toString` names neither user: who blocked whom is the most
 * sensitive pair of ids in the schema (T-09).
 */
@Entity
@Table(name = "blocks")
internal class BlockEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var blockerUserId: UUID,
    @Column(nullable = false, updatable = false)
    var blockedUserId: UUID,
    @Column(nullable = false, updatable = false)
    var bondId: UUID,
    @Column(nullable = false, updatable = false)
    var createdAt: Instant,
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

    override fun equals(other: Any?): Boolean = this === other || (other is BlockEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "BlockEntity(id=$id, bondId=$bondId)"
}
