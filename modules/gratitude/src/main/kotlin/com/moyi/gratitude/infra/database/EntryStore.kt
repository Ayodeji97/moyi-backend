package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Entries, spoken in domain terms — the `BondStore`/`BondDayStore`
 * precedent.
 *
 * No id minting here: [Entry.submit] already took its [com.moyi.gratitude.domain.EntryId]
 * as a parameter (from the injected [com.moyi.common.core.IdGenerator]'s
 * `opaque()`, one layer up), so [insert] only ever persists an [Entry] that
 * already exists.
 *
 * Transactional itself, for the reason [BondDayStore]'s own KDoc gives:
 * [EntryRepository] is declared on Spring Data's bare `Repository` marker,
 * which gets no transaction for free the way `JpaRepository`'s own methods
 * do.
 */
@Component
internal class EntryStore(
    private val entries: EntryRepository,
) {
    /**
     * Writes one entry. `entries_one_per_member_per_day` (V12) is what
     * refuses a second one for the same member on the same day — BR-2
     * enforced by the unique index, not checked here first.
     */
    @Transactional
    fun insert(entry: Entry) {
        entries.save(entry.toEntity())
    }

    /** Every entry filed against one day — BR-2 caps this at two, but nothing here assumes it. */
    @Transactional(readOnly = true)
    fun findForDay(bondDayId: BondDayId): List<Entry> = entries.findAllByBondDayId(bondDayId.value).map { it.toDomain() }
}
