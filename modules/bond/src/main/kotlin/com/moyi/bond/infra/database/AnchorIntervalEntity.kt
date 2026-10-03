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
import java.time.LocalDate
import java.util.UUID

/**
 * The `bond_anchor_intervals` row — [BondEntity]'s shape, for the same two
 * reasons given there: `@Id` assigned in application code via `IdGenerator`
 * (so [Persistable] saves without the wasted `SELECT` before every `INSERT`),
 * and never a `data class` (`copy()` on a managed entity produces a detached
 * twin the persistence context has never heard of).
 *
 * Only [effectiveTo] is ever written after construction: it is the one thing
 * [AnchorIntervalStore.scheduleHandoff] changes when it closes the open
 * interval, so it alone is `var`. Everything else about a row is fixed the
 * moment it is inserted.
 */
@Entity
@Table(name = "bond_anchor_intervals")
internal class AnchorIntervalEntity(
    // Private with an explicit `getId()`, for the reason `BondEntity` gives:
    // a public `val id` would generate a getter clashing with the one
    // `Persistable` requires.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(name = "bond_id", nullable = false, updatable = false)
    val bondId: UUID,
    @Column(nullable = false, updatable = false)
    val zone: String,
    @Column(name = "first_label", nullable = false, updatable = false)
    val firstLabel: LocalDate,
    @Column(name = "effective_from", nullable = false, updatable = false)
    val effectiveFrom: Instant,
    @Column(name = "effective_to")
    var effectiveTo: Instant?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
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

    override fun equals(other: Any?): Boolean = this === other || (other is AnchorIntervalEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AnchorIntervalEntity(id=$id, bondId=$bondId, zone=$zone, effectiveTo=$effectiveTo)"
}
