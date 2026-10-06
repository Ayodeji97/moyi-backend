package com.moyi.common.events

import java.time.Instant
import java.util.UUID

/** An event as a consumer receives it: what was published, and its id to deduplicate by. */
data class ReceivedEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val references: Map<String, UUID>,
    val occurredAt: Instant,
)

/**
 * Where a consumer's history begins the first time it subscribes to an event type.
 * It is asked once per (consumer, event type): changing it later moves nothing,
 * because the subscription row that would trigger a backfill already exists.
 */
enum class StartFrom {
    /** Every retained event of the type, including those published before the consumer existed. */
    BEGINNING,

    /** Only events published after the consumer registered. */
    NOW,
}

/**
 * Something that reacts to published events, at least once each and in no promised
 * order: a handler must give the same result whatever order events arrive in, and
 * must tolerate the same event twice.
 */
interface EventConsumer {
    /** Stable for ever: it is the key delivery state is kept under. */
    val id: String
    val eventTypes: Set<String>
    val startFrom: StartFrom

    /** Runs in the delivery's transaction. Throwing rolls it back and schedules a retry. */
    fun handle(event: ReceivedEvent)
}
