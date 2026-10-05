package com.moyi.common.events

import java.time.Instant
import java.util.UUID

/**
 * An event is recorded inside the caller's transaction, never after commit.
 * Payloads name resources by identity, never retain their content. C5 adds
 * consumer registration and delivery; no acknowledgement exists at publication.
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
