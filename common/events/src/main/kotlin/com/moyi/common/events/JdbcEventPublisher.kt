package com.moyi.common.events

import com.moyi.common.core.IdGenerator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

/** A mandatory transaction makes accidental publication outside the domain write fail loudly. */
@Component
internal class JdbcEventPublisher(
    private val jdbc: JdbcTemplate,
    private val ids: IdGenerator,
    private val mapper: ObjectMapper,
) : EventPublisher {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(event: OutboxEvent) {
        jdbc.update(
            """
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            ids.timeOrdered(),
            event.aggregateType,
            event.aggregateId,
            event.eventType,
            mapper.writeValueAsString(event.references),
            Timestamp.from(event.occurredAt.truncatedTo(ChronoUnit.MICROS)),
        )
    }
}
