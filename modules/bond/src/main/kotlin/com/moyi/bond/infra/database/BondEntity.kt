package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.BondType
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
import java.time.LocalTime
import java.util.UUID

/**
 * The `bonds` row. A persistence detail, kept out of the domain on purpose
 * (doc 25 §6, and the Konsist rule pinning `@Entity` to `infra`).
 *
 * `UserEntity`'s KDoc carries the two decisions this repeats and they are
 * worth re-reading there: why it implements [Persistable] (our ids are
 * assigned in application code, so `save()` would otherwise `SELECT` before
 * every `INSERT`), and why it is **not** a `data class` (`copy()` on a managed
 * entity produces a detached twin the persistence context has never heard of).
 *
 * New here: `@Version` on [version]. Hibernate appends `WHERE version = ?` to
 * every `UPDATE` and bumps the column, which is what will make slice B4's
 * `If-Match` a real check under concurrency rather than a comparison the
 * application performs and then forgets to honour. [maxMembers] is a `Short`
 * because the column is `smallint`; an `Int` fails `ddl-auto: validate` at
 * startup, which is the schema and the mapping being held to each other.
 */
@Entity
@Table(name = "bonds")
internal class BondEntity(
    // Private with an explicit `getId()` below, for the reason UserEntity
    // gives: a public `val id` would generate a getter clashing with the one
    // Persistable requires.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: BondType,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var anchorTimezone: String,
    var timezoneChangedAt: Instant?,
    var revealTimeLocal: LocalTime?,
    @Column(nullable = false)
    var strictMode: Boolean,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: BondStatus,
    @Column(nullable = false)
    var maxMembers: Short,
    @Column(nullable = false, updatable = false)
    var createdBy: UUID,
    @Column(nullable = false, updatable = false)
    var createdAt: Instant,
    var archivedAt: Instant?,
    var deletionRequestedAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is BondEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    /** No name: a bond's name is the couple's words and has no business in a log (doc 18 §9). */
    override fun toString(): String = "BondEntity(id=$id, status=$status)"
}
