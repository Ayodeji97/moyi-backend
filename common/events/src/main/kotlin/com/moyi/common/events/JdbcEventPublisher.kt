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
 *
 * **Only at READ COMMITTED, and the insert itself checks.** "Sees what it
 * committed" is true only where each statement takes a fresh snapshot. At
 * REPEATABLE READ or SERIALIZABLE a publisher held back by a registration
 * resumes on the snapshot it began with, finds no subscription, and commits an
 * event that is owed to nobody, with nothing to say so. So the event is
 * inserted only if the transaction's level is one that re-reads, and a
 * publication that inserted nothing is refused.
 *
 * The level is asked of the database, in the insert, rather than of Spring.
 * Spring knows only what a `@Transactional` asked for: its answer is null for
 * "the default", and it cannot see a default changed on the role or database,
 * nor a `SET TRANSACTION` issued by hand. Postgres' answer is the level the
 * statement is really running at, and folded into the insert it costs no
 * round trip. (READ UNCOMMITTED is let through because Postgres runs it as
 * READ COMMITTED.)
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
        val inserted =
            jdbc.update(
                """
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                SELECT ?, ?, ?, ?, ?::jsonb, ?
                WHERE current_setting('transaction_isolation') IN ('read committed', 'read uncommitted')
                """.trimIndent(),
                id,
                event.aggregateType,
                event.aggregateId,
                event.eventType,
                mapper.writeValueAsString(event.references),
                occurredAt,
            )
        check(inserted == 1) {
            "An event may be published only in a READ COMMITTED transaction: at a stricter level a publisher " +
                "held back by a registering consumer would not see its subscription, and the event would never be delivered to it."
        }
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
