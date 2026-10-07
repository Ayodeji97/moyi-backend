package com.moyi.common.events

import java.time.Instant
import java.util.UUID

/**
 * An event is recorded inside the caller's transaction, never after commit.
 * Payloads name resources by identity, never retain their content. Publication
 * also records what is owed: one delivery for each [EventConsumer] subscribed to
 * the event's type at that moment. Nothing is acknowledged at publication.
 */
interface EventPublisher {
    fun publish(event: OutboxEvent)
}

/** Content-free history. UUID-valued references cannot accidentally carry entry text. */
data class OutboxEvent(
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val references: Map<String, UUID>,
    val occurredAt: Instant,
)
