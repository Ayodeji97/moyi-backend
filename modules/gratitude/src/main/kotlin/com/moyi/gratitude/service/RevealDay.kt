package com.moyi.gratitude.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalTime

/**
 * Persists the domain's reveal and its event together. Callers hold the day
 * lock before reading the aggregate. `CloseDay` uses this same transition
 * under the same bond lock, which it takes through `BondAccess`'s locking
 * view for the closer (ADR-0033 decision 3); the closer owns when to sweep,
 * never a second reveal rule.
 *
 * **On a bond that has ended, the caller has first erased what was
 * withdrawn** ([EraseWithdrawnEntries]) and hands this the day as that left
 * it. This asks only the day and its rows, and neither knows that a
 * withdrawal is waiting for its erasure.
 */
@Service
internal class RevealDay(
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val events: EventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(
        day: BondDay,
        revealTime: LocalTime?,
        now: Instant,
    ): BondDay {
        val next = day.revealWhenDue(revealTime, now)
        if (day.revealedAt == null && next.revealedAt != null) {
            // Fresh: this writes each entry back, and one may have been loaded before the lock.
            entries.findForDayFresh(day.id).forEach { entry ->
                val revealed = entry.reveal(next.revealedAt)
                if (revealed != entry) entries.update(revealed)
            }
            events.publish(OutboxEvent("BondDay", day.id.value, "DayRevealed", mapOf("bondId" to day.bondId), next.revealedAt))
        }
        days.update(next)
        return next
    }
}
