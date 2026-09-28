package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
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

    /** Every entry filed against one day — BR-2 caps this at two, but nothing here assumes it. */
    fun findForDay(bondDayId: BondDayId): List<Entry> = entries.findAllByBondDayId(bondDayId.value).map { it.toDomain() }
}
