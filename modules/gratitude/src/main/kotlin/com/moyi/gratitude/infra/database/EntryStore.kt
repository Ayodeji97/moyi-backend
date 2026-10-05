package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

/**
 * Entries, spoken in domain terms — the `BondStore`/`BondDayStore`
 * precedent.
 *
 * No id minting here: [Entry.submit] already took its [com.moyi.gratitude.domain.EntryId]
 * as a parameter (from the injected [com.moyi.common.core.IdGenerator]'s
 * `opaque()`, one layer up), so [insert] only ever persists an [Entry] that
 * already exists.
 *
 * Not transactional itself, for the reason [BondDayStore]'s own KDoc gives:
 * the boundary is the caller's.
 */
@Component
internal class EntryStore(
    private val entries: EntryRepository,
    private val entityManager: EntityManager,
) {
    /**
     * Writes one entry, and flushes it. `entries_one_per_member_per_day`
     * (V12) is what refuses a second one for the same member on the same
     * day — BR-2 enforced by the unique index, not checked here first — and
     * the flush is what makes that violation a `DataIntegrityViolationException`
     * a caller can catch from *this* call, rather than one that only
     * surfaces at whatever transaction eventually commits (see
     * [EntryRepository.saveAndFlush]'s own KDoc).
     */
    fun insert(entry: Entry) {
        entries.saveAndFlush(entry.toEntity())
    }

    /** Refresh is necessary because routing loaded this entity before either lock. */
    fun lockAndFind(id: EntryId): Entry {
        entries.lockRow(id.value)
        val entity = checkNotNull(entries.findById(id.value)) { "a locked entry must exist" }
        entityManager.refresh(entity)
        return entity.toDomain()
    }

    /** Updates a managed row while the caller holds its parent day's lock. */
    fun update(entry: Entry) {
        val entity = checkNotNull(entries.findById(entry.id.value)) { "cannot update a missing entry" }
        entity.text = entry.text?.value
        entity.status = entry.status
        entity.updatedAt = entry.updatedAt
        entity.revealedAt = entity.revealedAt ?: entry.revealedAt
        entity.deletedAt = entry.deletedAt
        entity.imageMediaId = entry.imageMediaId
        entity.voiceMediaId = entry.voiceMediaId
        entity.voiceDurationMs = entry.voiceDurationMs
        entries.saveAndFlush(entity)
    }

    /**
     * Every entry filed against one day — BR-2 caps this at two, but nothing
     * here assumes it.
     *
     * **Returns an erased row too** (whole-branch review, F5), and that is
     * no longer a leak waiting to happen: every entry leaves the service
     * layer through [Entry.readBy], and BR-1 answers an erased entry as a
     * tombstone for everyone (ADR-0031 decision 10). What is still owed is
     * *which* row a caller picks once one author can have two on a day — an
     * erased one and its replacement. `GetToday` takes the first it finds;
     * the slice that first sets `deleted_at` must make that choice
     * deterministic (ADR-0031, Owed, C2).
     */
    fun findForDay(bondDayId: BondDayId): List<Entry> = entries.findAllByBondDayId(bondDayId.value).map { it.toDomain() }

    /**
     * One entry by id, **as it stands now** — a tombstone included (its
     * `text` is `null`). No authorisation here: the caller checks the bond
     * and the author before trusting what this returns.
     */
    fun find(id: EntryId): Entry? = entries.findById(id.value)?.toDomain()
}
