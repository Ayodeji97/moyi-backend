package com.moyi.common.events

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Hands published events to the [EventConsumer]s of this process, at least once
 * each and in no promised order (spec §8).
 */
interface OutboxDispatcher {
    /** Delivers what is due at [now], at most [budget] deliveries. Safe to run concurrently. */
    fun dispatchDue(
        now: Instant,
        budget: Int,
    ): DispatchResult

    /**
     * One [ConsumerBacklog] per registered consumer, as things stand at [now]:
     * how many of its deliveries are due and unprocessed, how many are
     * unprocessed after five failures or more, and the age of the oldest event
     * it has not yet handled.
     */
    fun backlog(now: Instant): List<ConsumerBacklog>
}

/**
 * What one run did. [delivered] and [failed] together never exceed the budget;
 * [more] says a delivery this process could claim was still due when the run ended.
 */
data class DispatchResult(
    val delivered: Int,
    val failed: Int,
    val more: Boolean,
)

/**
 * One registered consumer's queue. [pending] is what is due and unprocessed;
 * [failing] is what is unprocessed after five failures or more, due or not,
 * and is the nearest thing here to a dead-letter queue.
 *
 * [oldestAgeSeconds] is the event's age, not the delivery's wait: the seconds
 * between `now` and the `occurred_at` of the oldest event that has an
 * unprocessed delivery for this consumer, **whether that delivery is due or
 * not**, and zero when it has none. A delivery that keeps failing is due again
 * only every fifteen minutes at most; measured from when it last fell due it
 * would never look older than that, and an alert on "oldest event older than
 * fifteen minutes" could never fire for the one case it most needs to.
 */
data class ConsumerBacklog(
    val consumerId: String,
    val pending: Long,
    val failing: Long,
    val oldestAgeSeconds: Long,
)

/**
 * **One delivery, one transaction** (plan C5a, decision 4). The claim, the
 * handler and the acknowledgement share it, so a handler's work and the record
 * that it was done commit or vanish together: there is no moment at which an
 * entry is erased and the delivery still owed, or the reverse. A handler marked
 * `@Transactional(MANDATORY)` joins it.
 *
 * **Only what this process can handle is claimed**: a delivery whose consumer
 * has a bean here that declares the event's type. Any other is another
 * instance's, and is neither failed nor counted as work that remains.
 *
 * **The row lock is the concurrency control** (decision 7). `FOR UPDATE` holds
 * the claimed delivery until the transaction ends, and `SKIP LOCKED` makes a
 * second dispatcher pass over it rather than wait, so two instances polling at
 * once share the work and never run one delivery twice at the same time.
 *
 * **A failure is recorded after the rollback, in a statement of its own**,
 * because the rollback that undoes the handler's work would undo the record
 * too. Until that statement lands the delivery is unlocked and still due, so
 * another dispatcher may try it once more first: at least once, never exactly.
 * The record is written to survive that: it touches only a delivery nobody has
 * acknowledged since, it counts from the attempts it finds in the row and not
 * from what this dispatcher read, and it can move the next attempt later but
 * never earlier.
 *
 * **What an exception says never leaves it.** A handler works on entries, and
 * an exception raised while doing so may quote one. So `last_error` and the
 * log line carry the exception's class name and nothing else: no message, and
 * no throwable handed to the logger, whose stack trace would print the message.
 * For the same reason the handler's exception never leaves the transaction's
 * callback: Spring's `TransactionTemplate` logs an exception that passes
 * through it, whole, at DEBUG on every rollback and at ERROR when the rollback
 * itself fails. It is caught inside, the transaction is marked for rollback,
 * and the exception is carried out as a value Spring never sees.
 *
 * The clock is the caller's: every instant written or compared here is the
 * `now` handed in, which is what lets a test move time and a poller meter it.
 */
@Component
internal class JdbcOutboxDispatcher(
    private val registry: ConsumerRegistry,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val mapper: ObjectMapper,
) : OutboxDispatcher {
    private val transactions = TransactionTemplate(transactionManager)
    private val log = LoggerFactory.getLogger(javaClass)

    override fun dispatchDue(
        now: Instant,
        budget: Int,
    ): DispatchResult {
        require(budget > 0) { "A run needs a budget of at least one delivery." }
        // Inside a caller's transaction every delivery would join it: one
        // handler's failure would doom the rest, and each claim would be held
        // to the end of the run.
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "The dispatcher opens one transaction per delivery and cannot run inside another."
        }
        val handled = registry.consumers().flatMap { consumer -> consumer.eventTypes.map { consumer.id to it } }
        // No consumer in this process: nothing here is ours to claim, and a
        // context that only publishes pays no statement for having this bean.
        if (handled.isEmpty()) return DispatchResult(delivered = 0, failed = 0, more = false)
        val run =
            Run(
                now = now.truncatedTo(ChronoUnit.MICROS),
                consumerIds = handled.map { it.first }.toTypedArray(),
                eventTypes = handled.map { it.second }.toTypedArray(),
            )

        var delivered = 0
        var failed = 0
        var drained = false
        // Failures count towards the budget, which is what ends a run in which everything fails.
        // A delivery acknowledged elsewhere counts as neither: it was delivered, but not by this
        // run. It cannot be claimed again, so passing over it uncounted still ends the loop.
        while (!drained && delivered + failed < budget) {
            when (deliverOne(run)) {
                Outcome.DELIVERED -> delivered++
                Outcome.FAILED -> failed++
                Outcome.ACKNOWLEDGED_ELSEWHERE -> Unit
                Outcome.NOTHING_DUE -> drained = true
            }
        }
        // Drained, the only work left is what this run set aside: still due and
        // still claimable, by the next run. Stopped by the budget, the table is asked.
        val more = if (drained) run.setAside.isNotEmpty() else anythingClaimable(run)
        return DispatchResult(delivered, failed, more)
    }

    /**
     * Catches `Throwable`, twice, on purpose.
     *
     * **Inside the callback**, whatever the handler throws (or the reading of
     * the payload, or the acknowledgement) is kept, the transaction is marked
     * rollback-only, and the callback returns normally. A delivery whose
     * handler throws anything at all must be rolled back and must step aside
     * with a later `next_attempt_at`; left as it was, it would be the first
     * row claimed by every run from then on, and nothing behind it would ever
     * be delivered. And it is kept in here, not allowed out through the
     * template, so that no logger of Spring's is ever handed it.
     *
     * **Outside**, what is caught is the transaction machinery's own: the
     * claim could not be made, or the commit or the rollback failed. With
     * nothing claimed there is no delivery to blame, and that is the caller's
     * to hear about. With a claim, the delivery failed: for the handler's
     * reason if it had one (a rollback that then failed too changes nothing
     * about why), and otherwise for the commit's.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun deliverOne(run: Run): Outcome {
        var claimed: Claim? = null
        var handlerFailure: Throwable? = null
        val failure: Throwable =
            try {
                val outcome =
                    transactions.execute { status ->
                        val claim = claim(run) ?: return@execute Outcome.NOTHING_DUE
                        claimed = claim
                        try {
                            // Read only now, with the claim known: an unreadable payload is
                            // this delivery's failure, to be recorded, not the run's.
                            val consumer = registry.consumer(claim.consumerId)
                            checkNotNull(consumer) { "Claimed a delivery for a consumer this process lacks." }
                            consumer.handle(claim.toEvent())
                            acknowledge(claim, run)
                            Outcome.DELIVERED
                        } catch (thrown: Throwable) {
                            handlerFailure = thrown
                            status.setRollbackOnly()
                            Outcome.FAILED
                        }
                    }
                handlerFailure ?: return outcome
            } catch (thrown: Throwable) {
                handlerFailure ?: thrown
            }
        val claim = claimed ?: throw failure
        val recorded = recordFailure(claim, failure, run)
        if (recorded == Recorded.NO) run.setAside += claim
        // Recorded first, so the delivery backs off; then let go, because
        // a process out of memory or stack should not be told all is well.
        if (failure is VirtualMachineError) throw failure
        return if (recorded == Recorded.ALREADY_ACKNOWLEDGED) Outcome.ACKNOWLEDGED_ELSEWHERE else Outcome.FAILED
    }

    private fun claim(run: Run): Claim? =
        jdbc
            .query(
                """
                SELECT d.event_id, d.consumer_id, d.attempts,
                       e.aggregate_type, e.aggregate_id, e.event_type, e.payload::text AS payload, e.occurred_at
                FROM outbox_deliveries d
                JOIN outbox_events e ON e.id = d.event_id
                WHERE d.processed_at IS NULL AND d.next_attempt_at <= ?
                  AND $OURS
                  AND NOT EXISTS (
                      SELECT 1 FROM unnest(?::uuid[], ?::text[]) AS aside (event_id, consumer_id)
                      WHERE aside.event_id = d.event_id AND aside.consumer_id = d.consumer_id
                  )
                ORDER BY d.next_attempt_at, d.event_id, d.consumer_id
                LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """.trimIndent(),
                { row, _ ->
                    Claim(
                        eventId = row.getObject("event_id", UUID::class.java),
                        consumerId = row.getString("consumer_id"),
                        attempts = row.getInt("attempts"),
                        aggregateType = row.getString("aggregate_type"),
                        aggregateId = row.getObject("aggregate_id", UUID::class.java),
                        eventType = row.getString("event_type"),
                        payload = row.getString("payload"),
                        occurredAt = row.getTimestamp("occurred_at").toInstant(),
                    )
                },
                Timestamp.from(run.now),
                run.consumerIds,
                run.eventTypes,
                run.setAside.map { it.eventId.toString() }.toTypedArray(),
                run.setAside.map { it.consumerId }.toTypedArray(),
            ).firstOrNull()

    private fun acknowledge(
        claim: Claim,
        run: Run,
    ) {
        val acknowledged =
            jdbc.update(
                "UPDATE outbox_deliveries SET processed_at = ?, attempts = attempts + 1 WHERE event_id = ? AND consumer_id = ?",
                Timestamp.from(run.now),
                claim.eventId,
                claim.consumerId,
            )
        check(acknowledged == 1) { "A claimed delivery was gone when it came to be acknowledged." }
    }

    /**
     * One statement outside any transaction, so a transaction of its own, and
     * written for the company it may have: between the rollback and this
     * statement the delivery was unlocked and due, and another dispatcher may
     * have tried it too.
     *
     * - **`AND processed_at IS NULL`.** If the other dispatcher succeeded, or
     *   this one's own commit landed before it reported failing, the delivery
     *   is acknowledged and there is no failure left to record. Nothing is
     *   written, and the caller is told so.
     * - **The wait is chosen by the count in the row**, `attempts + 1` as the
     *   statement finds it, not by what this dispatcher read when it claimed.
     *   Two dispatchers that both read zero are the first and second failure,
     *   and the second waits the second step. The steps are [Backoff]'s, handed
     *   in as a list so that the doubling is written once.
     * - **`greatest`.** Whoever writes last cannot bring the next attempt
     *   forward of what is already there.
     *
     * When the record cannot be made at all, the delivery is exactly as it was
     * (still due, nothing lost) and the caller keeps it out of the rest of this
     * run, or the run would spend its whole budget on it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun recordFailure(
        claim: Claim,
        failure: Throwable,
        run: Run,
    ): Recorded {
        val errorClass = failure.javaClass.name
        val attempts =
            try {
                jdbc
                    .query(
                        """
                        UPDATE outbox_deliveries AS d
                        SET attempts = d.attempts + 1,
                            next_attempt_at = greatest(
                                d.next_attempt_at,
                                ?::timestamptz
                                    + backoff.seconds[least(d.attempts + 1, cardinality(backoff.seconds))] * interval '1 second'
                            ),
                            last_error = ?
                        FROM (SELECT ?::bigint[] AS seconds) AS backoff
                        WHERE d.event_id = ? AND d.consumer_id = ? AND d.processed_at IS NULL
                        RETURNING d.attempts
                        """.trimIndent(),
                        { row, _ -> row.getInt("attempts") },
                        Timestamp.from(run.now),
                        errorClass,
                        BACKOFF_SECONDS,
                        claim.eventId,
                        claim.consumerId,
                    ).firstOrNull()
            } catch (recording: Exception) {
                // The class names only, here too: a driver's message may quote the statement's values.
                log.warn(
                    "Outbox delivery failed and the failure could not be recorded; it stays due: " +
                        "consumer={} event={} type={} error={} recording={}",
                    claim.consumerId,
                    claim.eventId,
                    claim.eventType,
                    errorClass,
                    recording.javaClass.name,
                )
                return Recorded.NO
            }
        if (attempts == null) {
            // Not a failure that will be retried, and not said to be one: the delivery is done.
            log.info(
                "Outbox delivery failed here but was acknowledged before that could be recorded; nothing is owed: " +
                    "consumer={} event={} type={} error={}",
                claim.consumerId,
                claim.eventId,
                claim.eventType,
                errorClass,
            )
        } else {
            log.warn(
                "Outbox delivery failed and will be retried: consumer={} event={} type={} attempts={} error={}",
                claim.consumerId,
                claim.eventId,
                claim.eventType,
                attempts,
                errorClass,
            )
        }
        return if (attempts == null) Recorded.ALREADY_ACKNOWLEDGED else Recorded.YES
    }

    /**
     * Asked with the same lock clause as the claim, so "claimable" means what
     * it means there: a delivery another dispatcher is holding is that
     * dispatcher's work, not work that remains. The lock lasts the statement.
     */
    private fun anythingClaimable(run: Run): Boolean =
        jdbc
            .query(
                """
                SELECT 1 FROM outbox_deliveries d
                JOIN outbox_events e ON e.id = d.event_id
                WHERE d.processed_at IS NULL AND d.next_attempt_at <= ? AND $OURS
                LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """.trimIndent(),
                { _, _ -> true },
                Timestamp.from(run.now),
                run.consumerIds,
                run.eventTypes,
            ).isNotEmpty()

    override fun backlog(now: Instant): List<ConsumerBacklog> {
        val at = now.truncatedTo(ChronoUnit.MICROS)
        // From the consumers, not the deliveries, so one with nothing owed is
        // still reported: a gauge fed from this has to be able to return to zero.
        return jdbc.query(
            """
            SELECT c.consumer_id,
                   count(d.event_id) FILTER (WHERE d.next_attempt_at <= ?) AS pending,
                   count(d.event_id) FILTER (WHERE d.attempts >= $FAILING_FROM) AS failing,
                   min(e.occurred_at) AS oldest_unhandled
            FROM outbox_consumers c
            LEFT JOIN outbox_deliveries d ON d.consumer_id = c.consumer_id AND d.processed_at IS NULL
            LEFT JOIN outbox_events e ON e.id = d.event_id
            GROUP BY c.consumer_id
            ORDER BY c.consumer_id
            """.trimIndent(),
            { row, _ ->
                ConsumerBacklog(
                    consumerId = row.getString("consumer_id"),
                    pending = row.getLong("pending"),
                    failing = row.getLong("failing"),
                    // Never negative: an event stamped ahead of this clock has no age yet.
                    oldestAgeSeconds =
                        row.getTimestamp("oldest_unhandled")?.let { Duration.between(it.toInstant(), at).seconds.coerceAtLeast(0) } ?: 0,
                )
            },
            Timestamp.from(at),
        )
    }

    /**
     * What one call to [dispatchDue] carries from delivery to delivery.
     * [consumerIds] and [eventTypes] are read in step: position by position
     * they are the (consumer, event type) pairs this process has a handler for.
     */
    private class Run(
        val now: Instant,
        val consumerIds: Array<String>,
        val eventTypes: Array<String>,
    ) {
        /** Failed in this run and not recorded as such, so still due: kept out of the claim until the run ends. */
        val setAside = mutableListOf<Claim>()
    }

    /** A delivery this transaction holds the lock on, with its event as stored. */
    @Suppress("LongParameterList") // A row, column for column; nothing to bundle them into.
    private inner class Claim(
        val eventId: UUID,
        val consumerId: String,
        val attempts: Int,
        val aggregateType: String,
        val aggregateId: UUID,
        val eventType: String,
        val payload: String,
        val occurredAt: Instant,
    ) {
        fun toEvent() = ReceivedEvent(eventId, aggregateType, aggregateId, eventType, mapper.readValue(payload, REFERENCES), occurredAt)
    }

    private enum class Outcome { DELIVERED, FAILED, ACKNOWLEDGED_ELSEWHERE, NOTHING_DUE }

    /** What became of the attempt to record a failure. */
    private enum class Recorded { YES, NO, ALREADY_ACKNOWLEDGED }

    private companion object {
        /** Decision 5: a delivery that has failed this many times is counted apart. */
        const val FAILING_FROM = 5

        /**
         * "A delivery this process may claim": its consumer has a bean here
         * **and that bean declares the event's type**. The id alone is not
         * enough. Deliveries outlive the subscription that made them, and in a
         * rolling deploy two builds of one consumer run side by side; a build
         * that never declared a type would take that type's deliveries from the
         * build that did, and acknowledge events it was not written to handle.
         * What fails the test is left unlocked and untouched, for the instance
         * that owns it, exactly as another consumer's delivery is.
         */
        const val OURS =
            "EXISTS (SELECT 1 FROM unnest(?::text[], ?::text[]) AS ours (consumer_id, event_type) " +
                "WHERE ours.consumer_id = d.consumer_id AND ours.event_type = e.event_type)"
        val REFERENCES = object : TypeReference<Map<String, UUID>>() {}

        /** [Backoff]'s schedule in seconds, as the failure record reads it: entry n is the wait after the nth failure. */
        val BACKOFF_SECONDS: Array<Long> = Backoff.steps().map { it.seconds }.toTypedArray()
    }
}
