package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

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
     * [findForDay] for a page of days, in **one** query however many days
     * there are, and none for no days. Erased rows included, as there. A day
     * with no entry has no key in the answer.
     */
    fun findForDays(bondDayIds: Collection<BondDayId>): Map<BondDayId, List<Entry>> =
        if (bondDayIds.isEmpty()) {
            emptyMap()
        } else {
            entries.findAllByBondDayIdIn(bondDayIds.map { it.value }).map { it.toDomain() }.groupBy { it.bondDayId }
        }

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
     * Where each live entry of one author in one bond is, **oldest day first**
     * — for the withdrawal, which erases them all and must take their days in
     * that order.
     *
     * "Live" is asked of the database here and is only a filter: it spares
     * reading years of tombstones. The rule itself is [Entry.isErased], which
     * the erasure asks again of each entry under its lock, so a row this
     * wrongly included would be passed over there.
     *
     * Read under the bond's lock, the list cannot grow or shrink while it is
     * worked through: every writer of an entry takes that lock first.
     */
    fun liveOf(
        bondId: UUID,
        authorMemberId: UUID,
    ): List<LiveEntry> =
        entries.findLiveOfAuthor(bondId, authorMemberId).map { (id, dayId) ->
            LiveEntry(EntryId(id as UUID), BondDayId(dayId as UUID))
        }

    /**
     * Writes what is pending and lets go of every entity this transaction has
     * loaded — for a caller working through thousands of entries in one
     * transaction, between one and the next.
     *
     * Hibernate checks every entity it holds for changes at each flush, and
     * each erasure flushes several times; kept, the two thousandth entry
     * would pay for the 1,999 before it. Measured on two thousand entries:
     * 9.2 s without this, 5.0 s with it, and the second grows in step with
     * the count where the first grows with its square.
     *
     * **Only for a caller that holds no entity and trusts no earlier read**:
     * anything loaded before this is detached, and a write to it afterwards
     * would be lost without a word. The stores hand out copies and read again
     * under each lock, so a caller that uses only them qualifies.
     */
    fun forgetLoaded() {
        entityManager.flush()
        entityManager.clear()
    }

    /**
     * One entry by id, **as it stands now** — a tombstone included (its
     * `text` is `null`). No authorisation here: the caller checks the bond
     * and the author before trusting what this returns.
     */
    fun find(id: EntryId): Entry? = entries.findById(id.value)?.toDomain()
}

/** An entry that has not been erased, by where to find it: its own id and its day's. */
internal data class LiveEntry(
    val id: EntryId,
    val bondDayId: BondDayId,
)
