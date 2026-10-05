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
 * lock before reading the aggregate. C3 can use this same transition without
 * taking the bond lock; it owns when to sweep, never a second reveal rule.
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
            entries.findForDay(day.id).forEach { entry ->
                val revealed = entry.reveal(next.revealedAt)
                if (revealed != entry) entries.update(revealed)
            }
            events.publish(OutboxEvent("BondDay", day.id.value, "DayRevealed", mapOf("bondId" to day.bondId), next.revealedAt))
        }
        days.update(next)
        return next
    }
}
