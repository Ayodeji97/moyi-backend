package com.moyi.bond.infra.database

import com.moyi.bond.domain.ProposalKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * The `bond_proposals` row.
 *
 * `bondId` and `proposedByMemberId` are plain columns rather than associations,
 * for the reason `BondMemberEntity` gives: the aggregate is small enough to
 * assemble in queries the code can see, and a lazy association decides for
 * itself when to go to the database.
 *
 * The state changes are all conditional `UPDATE`s in [BondProposalRepository]
 * rather than mutations of a loaded object — confirming has to be atomic with
 * the check that the proposal is still live — so the mutable fields here exist
 * only because Hibernate needs somewhere to put what it reads.
 */
@Entity
@Table(name = "bond_proposals")
internal class BondProposalEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var bondId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    var kind: ProposalKind,
    @Column(updatable = false)
    var payload: String?,
    @Column(nullable = false, updatable = false)
    var proposedByMemberId: UUID,
    @Column(nullable = false, updatable = false)
    var proposedAt: Instant,
    @Column(nullable = false, updatable = false)
    var expiresAt: Instant,
    var confirmedByMemberId: UUID?,
    var confirmedAt: Instant?,
    var cancelledAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is BondProposalEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    /** The kind, not the payload: a proposed zone says where somebody lives. */
    override fun toString(): String = "BondProposalEntity(id=$id, bondId=$bondId, kind=$kind)"
}
