package com.moyi.gratitude.infra.database

import com.moyi.common.core.IdGenerator
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The Bond-day, spoken in domain terms — the `BondStore` precedent
 * (`modules/bond`). A service deals in [BondDay] and never imports a
 * `toEntity`.
 *
 * **Transactional itself, unlike `BondStore`.** `BondStore`'s own KDoc
 * leaves the boundary to the caller because it coordinates *two* aggregates
 * a service writes together (a bond and its first invite). Nothing here
 * does: every method below is already one complete unit of work on its own,
 * and each needs its own transaction regardless of the caller, because this
 * module's repositories are declared on Spring Data's bare [org.springframework.data.repository.Repository]
 * marker (the `BondRepository` precedent) rather than `JpaRepository` —
 * which means none of `save`, `findById` or a `@Modifying` query gets a
 * transaction for free the way `SimpleJpaRepository`'s own methods do; each
 * call needs one already open, or (for a `@Modifying` query specifically)
 * throws `TransactionRequiredException`. `@Transactional` here is what
 * supplies it, so a caller — this class's own test included — can call
 * [openOrGet] or [update] exactly as it would call any other method, with
 * no `TransactionTemplate` of its own to remember.
 */
@Component
internal class BondDayStore(
    private val days: BondDayRepository,
    private val ids: IdGenerator,
) {
    /**
     * The first write to reach a bond's date opens it; every later one
     * finds the same row (doc 04 §3). [BondDayId] is minted here — from the
     * injected [IdGenerator], never inside [BondDayRepository.insertIfAbsent]
     * or the domain — because the caller that loses the race discards the id
     * it minted along with the row it never wrote, exactly as `BlockStore`'s
     * own `insertIfAbsent` does.
     */
    @Transactional
    fun openOrGet(
        bondId: UUID,
        date: LocalDate,
        zone: ZoneId,
        now: Instant,
    ): BondDay {
        val id = BondDayId(ids.timeOrdered())
        days.insertIfAbsent(id = id.value, bondId = bondId, date = date, anchorTimezone = zone.id, createdAt = now)
        return checkNotNull(days.findByBondIdAndDate(bondId, date)?.toDomain()) {
            "a bond-day for $bondId on $date must exist immediately after openOrGet"
        }
    }

    /** The day by its own id, for a caller that already holds one — `BondDayPersistenceTest`'s own check that the zone stuck. */
    @Transactional(readOnly = true)
    fun find(id: BondDayId): BondDay? = days.findById(id.value)?.toDomain()

    /**
     * Just the status, without loading the whole aggregate — see
     * [BondDayRepository.findStatusByBondIdAndDate]'s own KDoc for who this
     * is for.
     */
    @Transactional(readOnly = true)
    fun statusOf(
        bondId: UUID,
        date: LocalDate,
    ): BondDayStatus? = days.findStatusByBondIdAndDate(bondId, date)

    /**
     * Writes a changed [BondDay] — the reveal and close transitions later
     * slices add. [day] is the aggregate *after* its own transition, as
     * `BondStore.update`'s own KDoc describes; the same warning applies:
     * this is not the layer that prevents a lost update.
     */
    @Transactional
    fun update(day: BondDay) {
        val entity = days.findById(day.id.value) ?: error("cannot update a bond-day that does not exist")
        day.applyTo(entity)
        days.saveAndFlush(entity)
    }
}
