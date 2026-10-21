package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryStatus
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
 * The `entries` row. A persistence detail, kept out of the domain on
 * purpose — [BondDayEntity]'s KDoc gives the shared reasoning.
 *
 * `bondDayId`, `bondId` and `authorMemberId` are plain `UUID` columns with
 * no `@ManyToOne`: V12's own comment says why — no JPA object graph between
 * [BondDayEntity] and this one (ADR-0026), and `bondId`/`authorMemberId` are
 * ids `bond` owns, never a foreign key across the module boundary.
 *
 * **`textSearch` is the search projection** (V25, ADR-0039): what [searchProjectionOf] makes
 * of the text, written on the insert and on every update, so it is emptied with the words.
 * `search_vector` is generated from it by Postgres and is not mapped: nothing here reads or
 * writes it, and an unmapped column is not a validation failure under `ddl-auto: validate`.
 *
 * No `version`: unlike [BondDayEntity], nothing in this slice — or the next
 * one BR-1 anticipates — compares an entry against a client-supplied ETag.
 *
 * `toString` never prints `text` or `textSearch`: an entry's words are exactly what doc 18
 * §5/§9 forbids leaking into a log, the same reason `EntryText.toString` is
 * redacted in the domain.
 */
@Entity
@Table(name = "entries")
internal class EntryEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var bondDayId: UUID,
    @Column(nullable = false, updatable = false)
    var bondId: UUID,
    @Column(nullable = false, updatable = false)
    var authorMemberId: UUID,
    var text: String?,
    /** [searchProjectionOf] of the text, and nothing else ever (ADR-0039). Never printed: see [toString]. */
    var textSearch: String?,
    var imageMediaId: UUID?,
    var voiceMediaId: UUID?,
    var voiceDurationMs: Int?,
    var promptId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: EntryStatus,
    @Column(nullable = false)
    var authorDeletedAccount: Boolean,
    @Column(nullable = false, updatable = false)
    var createdAt: Instant,
    @Column(nullable = false)
    var intendedAt: Instant,
    @Column(nullable = false)
    var updatedAt: Instant,
    var revealedAt: Instant?,
    var deletedAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is EntryEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "EntryEntity(id=$id, bondDayId=$bondDayId, authorMemberId=$authorMemberId, status=$status)"
}
