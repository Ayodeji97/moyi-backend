package com.moyi.gratitude.infra.database

import com.moyi.common.core.IdGenerator
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The Bond-day, spoken in domain terms — the `BondStore` precedent
 * (`modules/bond`). A service deals in [BondDay] and never imports a
 * `toEntity`.
 *
 * Not transactional itself, for the reason `BondStore`'s own KDoc gives: the
 * boundary is the caller's (doc 18 §4). A caller of [openOrGet] or [update]
 * supplies one — a `TransactionTemplate`, in a test or in the services
 * that write a day (`SubmitEntry`, `ChangeEntry`, `ReconcileJoiningDay`) —
 * the same discipline `BondPersistenceTest` and `MemberPersistenceTest`
 * already hold every `bond` store to.
 */
@Component
internal class BondDayStore(
    private val days: BondDayRepository,
    private val ids: IdGenerator,
    private val entityManager: EntityManager,
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
     *
     * **[window] is the day, as the bond's calendar resolved it** — the label
     * and the UTC span `[startsAt, endsAt)` both, persisted as given (spec
     * §3.1). Nothing here derives a span from [zone]; [zone] is only the
     * snapshot `anchor_timezone` keeps. The loser of the race gets the row the
     * winner wrote, span included, which is what an existing day must be:
     * opened once, never recomputed (BR-6). **An existing row is returned as
     * it stands, even when [window] now ends later than it does** — a
     * westward anchor change agreed since the row was opened (plan R3). This
     * method does not reconcile that; a writer does, under the day's lock,
     * with [BondDay.extendedTo] and [update] (ruling P10). A row nobody
     * writes to again keeps its shorter `ends_at` until C3's close job
     * reconciles it from the timeline. Instants are truncated to
     * microseconds, Postgres's own `timestamptz` resolution, so the row read
     * back is the row written.
     */
    fun openOrGet(
        bondId: UUID,
        window: DayWindow,
        zone: ZoneId,
        now: Instant,
        status: BondDayStatus = BondDayStatus.OPEN,
    ): BondDay {
        val id = BondDayId(ids.timeOrdered())
        days.insertIfAbsent(
            id = id.value,
            bondId = bondId,
            date = window.date,
            status = status.name,
            anchorTimezone = zone.id,
            startsAt = window.startsAt.truncatedTo(ChronoUnit.MICROS),
            endsAt = window.endsAt.truncatedTo(ChronoUnit.MICROS),
            createdAt = now.truncatedTo(ChronoUnit.MICROS),
        )
        return checkNotNull(days.findByBondIdAndDate(bondId, window.date)?.toDomain()) {
            "a bond-day for $bondId on ${window.date} must exist immediately after openOrGet"
        }
    }

    /** The day by its own id, for a caller that already holds one — `BondDayPersistenceTest`'s own check that the zone stuck. */
    fun find(id: BondDayId): BondDay? = days.findById(id.value)?.toDomain()

    /**
     * The day for a bond and a date, if one has ever been opened — `null`,
     * not created, when nobody has written yet.
     *
     * **This is the read for a caller that must not open the row, and the
     * reason it exists separately from [openOrGet]: a `GET` must never
     * manufacture the row [openOrGet] exists to lazily open for a write.**
     * `GetToday` asks "does this day exist" and accepts "no" as a real,
     * cheap answer, rather than opening it to find out. So does
     * `ReconcileJoiningDay`, which looks for a couple's joining day and
     * leaves a missing one missing, and so does `SubmitEntry`'s BR-3a check
     * of whether a claimed day is already settled. Delegates to
     * the same [BondDayRepository.findByBondIdAndDate] [openOrGet] already
     * uses internally to read back what it just opened or found — this is
     * that same query, exposed for a caller with no write to make first.
     */
    fun findByBondAndDate(
        bondId: UUID,
        date: LocalDate,
    ): BondDay? = days.findByBondIdAndDate(bondId, date)?.toDomain()

    /**
     * Holds the day's row for the rest of the transaction, then reads it back
     * fresh under that lock (fix round 1, I3). Every writer of a day comes
     * through here first — `SubmitEntry`, `ChangeEntry` and
     * `ReconcileJoiningDay` — and `SubmitEntry`'s use is the one that shows
     * why: `entry_count`/`status` is a read-modify-write — [BondDay.withEntry] is
     * computed in application code from whatever was last read, then [update]
     * writes it back — and two members submitting on the same day
     * concurrently is the ordinary case this product exists for, not a race
     * to guard against. Without a lock taken *before* that read, both
     * transactions read the same `entry_count`, both compute `+1`, and the
     * second's flush trips `@Version`: `ObjectOptimisticLockingFailureException`,
     * uncaught, a `500`. See [BondDayRepository.lockRow]'s own KDoc for the
     * precedent this follows (`bond.infra.database.BondRepositories.lockRow`,
     * ADR-0028's lock rule) and why a row lock rather than an advisory one.
     */
    fun lockAndFind(id: BondDayId): BondDay {
        days.lockRow(id.value)
        val entity =
            checkNotNull(days.findById(id.value)) {
                "a bond-day locked by lockAndFind must still exist: $id"
            }
        // The lock alone is not enough: if `entity` was already loaded into
        // this transaction's persistence context — exactly what happens here,
        // by `openOrGet`'s own `findByBondIdAndDate` moments earlier —
        // `findById` above answers from that identity map rather than the
        // database, and returns the *same stale Java object*, lock or no
        // lock. Found the hard way: `SubmitEntryConcurrencyTest` failed with
        // a `500` even with `lockRow` wired in, because the entity `find`
        // returned was the one read before the lock was ever taken.
        // `refresh` is what forces Hibernate to re-populate it from the row
        // this transaction now holds exclusively.
        entityManager.refresh(entity)
        return entity.toDomain()
    }

    /**
     * Writes a changed [BondDay] — an entry counted or taken back, a span
     * extended ([BondDay.extendedTo]), a joining day resumed, a reveal
     * ([BondDay.revealWhenDue]); the close transitions are C3's to add.
     * [day] is the aggregate *after* its own transition, as
     * `BondStore.update`'s own KDoc describes; the same warning applies:
     * this is not the layer that prevents a lost update.
     */
    fun update(day: BondDay) {
        val entity = days.findById(day.id.value) ?: error("cannot update a bond-day that does not exist")
        day.applyTo(entity)
        days.saveAndFlush(entity)
    }
}
