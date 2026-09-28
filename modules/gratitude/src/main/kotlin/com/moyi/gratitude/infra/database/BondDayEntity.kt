package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.Version
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The `bond_days` row. A persistence detail, kept out of the domain on
 * purpose (doc 25 §6, and the Konsist rule pinning `@Entity` to `infra`).
 *
 * `BondEntity`'s KDoc (`modules/bond`) carries the two decisions this
 * repeats, and they are worth re-reading there: why it implements
 * [Persistable] (this module's ids are assigned in application code, so
 * `save()` would otherwise `SELECT` before every `INSERT`), and why it is
 * **not** a `data class` (`copy()` on a managed entity produces a detached
 * twin the persistence context has never heard of).
 *
 * `status` is typed as the domain's own [BondDayStatus], not a persistence-
 * only mirror of it — the same choice `BondEntity` makes for `BondStatus`.
 * `entryCount` is a `Short` because the column is `smallint`; an `Int` fails
 * `ddl-auto: validate` at startup, the same reason `BondEntity.maxMembers`
 * is one.
 */
@Entity
@Table(name = "bond_days")
internal class BondDayEntity(
    // Private with an explicit `getId()` below, for the reason `BondEntity`
    // gives: a public `val id` would generate a getter clashing with the one
    // Persistable requires.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var bondId: UUID,
    @Column(nullable = false, updatable = false)
    var date: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: BondDayStatus,
    @Column(nullable = false, updatable = false)
    var anchorTimezone: String,
    @Column(nullable = false)
    var entryCount: Short,
    var revealedAt: Instant?,
    var closedAt: Instant?,
    @Column(nullable = false, updatable = false)
    var createdAt: Instant,
    @Version
    @Column(nullable = false)
    var version: Int,
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

    override fun equals(other: Any?): Boolean = this === other || (other is BondDayEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "BondDayEntity(id=$id, bondId=$bondId, date=$date, status=$status)"
}
