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
     * Includes erased rows for tombstones and replay. GetToday selects live
     * rows first, then the newest tombstone (createdAt and UUID tie-break), so
     * withdraw-then-rewrite never lets an unordered row hide the replacement.
     */
    fun findForDay(bondDayId: BondDayId): List<Entry> = entries.findAllByBondDayId(bondDayId.value).map { it.toDomain() }

    /**
     * [findForDay], with every row read again from the database — for a
     * caller about to **write** what it reads, under the day's lock.
     *
     * [findForDay] answers a row this transaction has already loaded from
     * Hibernate's identity map, as it stood when it was loaded; and a route
     * that finds its entry by id loads it before any lock is held. [update]
     * writes every column from the entry it is given, so a reveal working
     * from that older copy would write it back over whatever was committed
     * in between — an edit undone, or erased words restored
     * (`RevealFreshReadTest`). The same trap, and the same cure, as
     * [BondDayStore.lockAndFind]'s.
     */
    fun findForDayFresh(bondDayId: BondDayId): List<Entry> =
        entries.findAllByBondDayId(bondDayId.value).map { entity ->
            entityManager.refresh(entity)
            entity.toDomain()
        }

    /**
     * One entry by id, **as it stands now** — a tombstone included (its
     * `text` is `null`). No authorisation here: the caller checks the bond
     * and the author before trusting what this returns.
     */
    fun find(id: EntryId): Entry? = entries.findById(id.value)?.toDomain()
}
