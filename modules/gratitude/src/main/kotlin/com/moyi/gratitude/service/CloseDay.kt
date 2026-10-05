package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.redacted
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Settles **one** Bond-day (spec §6.4 step 2): one transaction, one row lock,
 * and no bond lock (ADR-0031 decision 18). The close job calls this once per
 * day that may have ended; nothing here knows there is a job.
 *
 * **Why one day at a time.** The writers that hold more than one day of a
 * bond (`SubmitEntry`, `ChangeEntry`) take them oldest first and under the
 * bond's lock. The closer holds no bond lock, so the only way it can never
 * be the other half of a deadlock with them is never to hold two days at
 * once. It also means a failure costs one day, and the next run retries it.
 *
 * **The order inside, and why each step is where it is:**
 *
 * 1. Lock the row and read it again. Nothing is decided from a day read
 *    before the lock: a submission may have been writing it.
 * 2. Bring its span up to the bond's timeline ([BondDay.extendedTo]). A row
 *    opened before a westward zone change still ends where the calendar said
 *    then; asked "has it ended?" on that stored value, a day the couple is
 *    still writing on closes up to a day early.
 * 3. Resume it if it is the joining day ([BondDay.resumeJoiningDay]) and
 *    apply the reveal rule ([RevealDay]) — the same two steps, by the same
 *    code, that the first request to meet the day would have taken. A
 *    joining day stamped closed before this would never be evaluated at all.
 *    This is also the second sweep of spec §6.3: a `PENDING_REVEAL` day
 *    whose time has come is revealed here, ended or not.
 * 4. Only now ask whether the day has ended. If not, keep what steps 2 and 3
 *    changed and stop.
 * 5. Close it ([BondDay.close]). A day both wrote on that was still waiting
 *    for its time is revealed first, as a reveal; on a `SOLO` day the lone
 *    entry is revealed (FR-063), read fresh under the lock.
 */
@Service
internal class CloseDay(
    private val access: BondAccess,
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val reveal: RevealDay,
    private val events: EventPublisher,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** What [settle] found and did. */
    enum class Outcome {
        /** The day had ended and is now closed. */
        CLOSED,

        /** Not ended, but its reveal fell due and both entries are now readable. */
        REVEALED,

        /** Not ended, and nothing was due. */
        NOT_YET,

        /** Closed already — by an earlier run, or by another instance a moment ago. */
        ALREADY_CLOSED,
    }

    fun settle(
        dayId: BondDayId,
        now: Instant,
    ): Outcome =
        try {
            checkNotNull(transactions.execute { underTheDayLock(dayId, now) })
        } catch (violation: DataIntegrityViolationException) {
            // The closer updates entries too (the SOLO reveal), and Postgres
            // reports a violated CHECK with the row. See `redacted`.
            throw violation.redacted()
        }

    private fun underTheDayLock(
        dayId: BondDayId,
        now: Instant,
    ): Outcome {
        val locked = days.lockAndFind(dayId)
        val view = if (locked.closedAt == null) access.closingViewOf(locked.bondId) else null
        if (view == null) {
            // No view of an unclosed day means its bond's row is gone. Left
            // alone: there is no timeline to say when the day ended.
            if (locked.closedAt == null) log.warn("close: bond {} of day {} was not found; the day is left as it is", locked.bondId, dayId)
            return Outcome.ALREADY_CLOSED
        }
        val window =
            checkNotNull(view.anchorTimeline.asCalendar().dayAt(locked.startsAt)) {
                "a bond-day starts no earlier than its bond's timeline: $dayId"
            }
        val current = reveal.apply(locked.extendedTo(window).resumeJoiningDay(view.activeSince), view.revealTimeLocal, now)
        return if (now.isBefore(current.endsAt)) {
            if (locked.revealedAt == null && current.revealedAt != null) Outcome.REVEALED else Outcome.NOT_YET
        } else {
            close(current, now)
            Outcome.CLOSED
        }
    }

    private fun close(
        ended: BondDay,
        now: Instant,
    ) {
        // The day's own end outranks a reveal time later than it. Done as a
        // reveal, so both entries are stamped and DayRevealed is written.
        val day = if (ended.status == BondDayStatus.PENDING_REVEAL) reveal.apply(ended, null, now) else ended
        val closed = day.close(now)
        val closedAt = checkNotNull(closed.closedAt)
        if (closed.status == BondDayStatus.SOLO) {
            entries.findForDayFresh(closed.id).forEach { entry ->
                val revealed = entry.reveal(closedAt)
                if (revealed != entry) entries.update(revealed)
            }
        }
        days.update(closed)
        // A suspended day is excluded from evaluation (doc 04 §8.3a): closing
        // it settles nothing anybody is waiting to hear about.
        if (closed.status != BondDayStatus.SUSPENDED) {
            events.publish(OutboxEvent("BondDay", closed.id.value, "DayClosed", mapOf("bondId" to closed.bondId), closedAt))
        }
    }
}
