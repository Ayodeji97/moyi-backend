package com.moyi.scheduling.service

import com.moyi.common.events.ConsumerBacklog
import com.moyi.common.events.OutboxDispatcher
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The outbox's poller (spec §8; plan C5a, Task 3). It knows the time and five
 * meters, and asks `common:events` to do the delivering: what a delivery is,
 * and what a failure does to it, is not written here.
 *
 * **Two seconds after the last tick finished**, a delay and not a rate: a tick
 * that takes long is followed by a pause, not by the ticks it overran. A
 * withdrawal is unreadable from the moment its request commits (the read gate
 * sees to that), so these two seconds are how long the text outlives it on
 * disk, not how long it can be read.
 *
 * **No `@SchedulerLock`, on purpose** (decision 7). The close job takes one
 * because two instances closing the same day is work done twice. Here the
 * dispatcher claims each delivery with `FOR UPDATE SKIP LOCKED`: that row lock
 * is the concurrency control, a second instance passes over what the first
 * holds, and two instances polling at once is the design, sharing the queue,
 * not a fault to be locked out. A lock here would only make one of them idle.
 *
 * **Not `@Transactional`, and it must stay that way.** The dispatcher opens one
 * transaction per delivery, so that one handler's failure rolls back that
 * delivery alone, and it refuses to run inside a caller's.
 *
 * **A tick goes round again while more is waiting, at most [MAX_PASSES]
 * times.** A queue longer than one budget should not sit for two seconds
 * between budgets. The bound is there because "more" can stay true without
 * anything being drained: a failure that cannot be recorded leaves its
 * delivery due, and a tick that looped until "more" was false would spin on it
 * for as long as that lasted. Bounded, a tick always ends, the pause follows,
 * and the next tick takes up what is left.
 *
 * **Nothing a tick meets may stop the next one, and nothing it meets may be
 * quoted.** Whatever is thrown (the database is away, mostly) is caught here
 * and logged by its class name alone: no message, and no throwable handed to
 * the logger, whose stack trace would print the message. Left to escape, the
 * scheduler would keep the schedule but log the exception whole, and an
 * exception raised around a handler may quote what the handler was working on.
 *
 * **The gauges are per consumer and registered once each.** The consumers are
 * whoever the backlog reports, so they are met at run time, not at
 * construction. Each gets its three gauges the first time it is seen, backed
 * by numbers this object keeps and overwrites after every tick. The map holds
 * those numbers for the life of the job, which matters: a Micrometer gauge
 * keeps only a weak reference to what it reads, and a number nobody else held
 * would be collected and the gauge would read NaN.
 *
 * The two counters carry no consumer tag, because a run reports its totals
 * and not whose deliveries they were; the per-consumer picture is the gauges'.
 */
@Component
internal class OutboxJob(
    private val dispatcher: OutboxDispatcher,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queues = ConcurrentHashMap<String, Queue>()
    private val delivered: Counter =
        Counter
            .builder(DELIVERED)
            .description("Outbox deliveries handled and acknowledged")
            .register(meters)
    private val failed: Counter =
        Counter
            .builder(FAILED)
            .description("Outbox deliveries whose handler failed; each is retried, so one delivery can be counted many times")
            .register(meters)

    @Scheduled(fixedDelayString = "\${moyi.scheduling.outbox.delay:$EVERY_TWO_SECONDS}")
    fun run() {
        dispatchOnce()
    }

    /**
     * One tick, without the timer — what [run] does. Never throws.
     *
     * `Throwable`, on purpose: the dispatcher rethrows a `VirtualMachineError`
     * once it has recorded the delivery that raised it, and that too must be
     * named here rather than printed by the scheduler.
     */
    @Suppress("TooGenericExceptionCaught")
    fun dispatchOnce() {
        try {
            var passes = 0
            var deliveredNow = 0
            var failedNow = 0
            var more: Boolean
            do {
                // The clock is read for each pass: what fell due while the last one ran is due now.
                val result = dispatcher.dispatchDue(clock.instant(), BUDGET)
                // Counted pass by pass, so a later pass that throws does not lose what this one did.
                delivered.increment(result.delivered.toDouble())
                failed.increment(result.failed.toDouble())
                deliveredNow += result.delivered
                failedNow += result.failed
                passes++
                more = result.more
            } while (more && passes < MAX_PASSES)
            refreshMeters()
            if (more) {
                // Still more after every pass a tick allows: a backlog, or a delivery that cannot be set aside.
                log.warn("outbox: delivered {}, failed {}, and more is waiting after {} passes", deliveredNow, failedNow, passes)
            } else if (deliveredNow + failedNow > 0) {
                log.info("outbox: delivered {}, failed {}", deliveredNow, failedNow)
            }
        } catch (thrown: Throwable) {
            log.warn("outbox: the tick did not finish and the next one will try again: error={}", thrown.javaClass.name)
        }
    }

    /**
     * Sets every gauge from the backlog as it stands. A consumer the backlog
     * has stopped reporting reads zero from then on: its gauges cannot be left
     * showing the last thing that was true of it.
     */
    fun refreshMeters() {
        val backlog = dispatcher.backlog(clock.instant())
        backlog.forEach { queues.computeIfAbsent(it.consumerId, ::register).set(it) }
        val reported = backlog.mapTo(mutableSetOf()) { it.consumerId }
        queues.forEach { (consumerId, queue) -> if (consumerId !in reported) queue.clear() }
    }

    /** Called once per consumer, by the map: a second registration under the same name and tag would be ignored anyway. */
    private fun register(consumerId: String): Queue {
        val queue = Queue()
        Gauge
            .builder(PENDING, queue.pending) { it.get().toDouble() }
            .description("Outbox deliveries due and not yet handled")
            .tag(CONSUMER_TAG, consumerId)
            .register(meters)
        Gauge
            .builder(AGE, queue.oldestAgeSeconds) { it.get().toDouble() }
            .description("Seconds since the oldest event this consumer has not yet handled, whether or not its delivery is due")
            .tag(CONSUMER_TAG, consumerId)
            .register(meters)
        Gauge
            .builder(FAILING, queue.failing) { it.get().toDouble() }
            .description("Outbox deliveries unhandled after five failures or more: the nearest thing to a dead-letter queue")
            .tag(CONSUMER_TAG, consumerId)
            .register(meters)
        return queue
    }

    /** What one consumer's three gauges read. */
    private class Queue {
        val pending = AtomicLong()
        val failing = AtomicLong()
        val oldestAgeSeconds = AtomicLong()

        fun set(to: ConsumerBacklog) {
            pending.set(to.pending)
            failing.set(to.failing)
            oldestAgeSeconds.set(to.oldestAgeSeconds)
        }

        fun clear() {
            pending.set(0)
            failing.set(0)
            oldestAgeSeconds.set(0)
        }
    }

    internal companion object {
        /**
         * The pause between ticks, unless `moyi.scheduling.outbox.delay` says
         * otherwise. Nothing sets it: two seconds is what the smoke run sees too.
         */
        const val EVERY_TWO_SECONDS = "PT2S"

        /** Deliveries one pass may take before it answers; a failure counts as one. */
        const val BUDGET = 200

        /** Passes one tick may make while more is waiting: ten budgets, then the pause. */
        const val MAX_PASSES = 10

        /** `gratitude_outbox_pending` once exported (doc 11). */
        const val PENDING = "gratitude.outbox.pending"

        /** `gratitude_outbox_age_seconds_max` once exported (doc 11): the alert on a stuck event reads this. */
        const val AGE = "gratitude.outbox.age.seconds.max"
        const val FAILING = "gratitude.outbox.failing"
        const val DELIVERED = "gratitude.outbox.delivered"
        const val FAILED = "gratitude.outbox.failed"
        const val CONSUMER_TAG = "consumer"
    }
}
