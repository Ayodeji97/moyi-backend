package com.moyi.gratitude.infra.database

import com.moyi.common.core.IdGenerator
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The Bond-day, spoken in domain terms — the `BondStore` precedent
 * (`modules/bond`). A service deals in [BondDay] and never imports a
 * `toEntity`.
 *
 * Not transactional itself, for the reason `BondStore`'s own KDoc gives: the
 * boundary is the caller's (doc 18 §4). A caller of [openOrGet] or [update]
 * supplies one — a `TransactionTemplate` in a test, a `@Transactional`
 * service method once one exists — the same discipline `BondPersistenceTest`
 * and `MemberPersistenceTest` already hold every `bond` store to.
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
     *
     * [status] defaults to [BondDayStatus.OPEN], the ordinary case; a caller
     * writing on behalf of a bond still waiting for its second member passes
     * [BondDayStatus.SUSPENDED] instead (doc 04 §8.3a). **This is the same
     * initial state [BondDay.open]/[BondDay.openSuspended] describe, stated
     * a second time, in SQL** — nothing in production calls either of those
     * two factories yet, because [insertIfAbsent] is the only path that ever
     * creates the row. The duplication is deliberate, not an oversight: it
     * is what lets [insertIfAbsent] stay a single native statement rather
     * than a round trip through a domain object it would immediately discard
     * for the loser of the race.
     */
    fun openOrGet(
        bondId: UUID,
        date: LocalDate,
        zone: ZoneId,
        now: Instant,
        status: BondDayStatus = BondDayStatus.OPEN,
    ): BondDay {
        val id = BondDayId(ids.timeOrdered())
        days.insertIfAbsent(id = id.value, bondId = bondId, date = date, status = status.name, anchorTimezone = zone.id, createdAt = now)
        return checkNotNull(days.findByBondIdAndDate(bondId, date)?.toDomain()) {
            "a bond-day for $bondId on $date must exist immediately after openOrGet"
        }
    }

    /** The day by its own id, for a caller that already holds one — `BondDayPersistenceTest`'s own check that the zone stuck. */
    fun find(id: BondDayId): BondDay? = days.findById(id.value)?.toDomain()

    /**
     * Just the status, without loading the whole aggregate — see
     * [BondDayRepository.findStatusByBondIdAndDate]'s own KDoc for who this
     * is for.
     */
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
    fun update(day: BondDay) {
        val entity = days.findById(day.id.value) ?: error("cannot update a bond-day that does not exist")
        day.applyTo(entity)
        days.saveAndFlush(entity)
    }
}
