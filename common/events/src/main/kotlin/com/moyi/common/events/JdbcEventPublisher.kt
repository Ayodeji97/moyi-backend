package com.moyi.common.events

import com.moyi.common.core.IdGenerator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

/**
 * A mandatory transaction makes accidental publication outside the domain write fail loudly.
 *
 * The event's deliveries are written here too, one for each consumer subscribed
 * to its type, in the same transaction: an event and what is owed for it commit
 * or vanish together, and no later scan has to work out which events were never
 * fanned out. The subscriptions are read after the event's insert, and that
 * order is the guarantee: the insert is what waits on a registration in
 * progress ([ConsumerRegistry]), so the read that follows sees what it committed.
 */
@Component
internal class JdbcEventPublisher(
    private val jdbc: JdbcTemplate,
    private val ids: IdGenerator,
    private val mapper: ObjectMapper,
) : EventPublisher {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(event: OutboxEvent) {
        val id = ids.timeOrdered()
        val occurredAt = Timestamp.from(event.occurredAt.truncatedTo(ChronoUnit.MICROS))
        jdbc.update(
            """
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            id,
            event.aggregateType,
            event.aggregateId,
            event.eventType,
            mapper.writeValueAsString(event.references),
            occurredAt,
        )
        // Due from the moment it happened: a delivery is never scheduled ahead of its event.
        jdbc.update(
            """
            INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at)
            SELECT ?, consumer_id, ? FROM outbox_subscriptions WHERE event_type = ?
            """.trimIndent(),
            id,
            occurredAt,
            event.eventType,
        )
    }
}
