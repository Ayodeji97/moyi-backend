package com.moyi.scheduling.service

import com.moyi.common.events.ConsumerBacklog
import com.moyi.common.events.OutboxDispatcher
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The outbox's poller (spec §8; ADR-0035 decisions 7 and 8). It knows the time and six
 * meters, and asks `common:events` to do the delivering: what a delivery is,
 * and what a failure does to it, is not written here.
 *
 * **Every two seconds, on a thread of its own.** A withdrawal is unreadable
 * from the moment its request commits (the read gate sees to that), so these
 * two seconds are how long the text outlives it on disk, not how long it can
 * be read.
 *
 * **The timer only hands the tick over** ([run]); the tick runs on one thread
 * this job owns ([dispatchOnce]). A handler can hang: on a lock nobody
 * releases, for as long as its delivery is allowed, and for longer on anything
 * that is not the database. Run on the scheduler's thread, that tick would
 * keep the thread, and the scheduler Spring Boot builds when virtual threads
 * are off has exactly one: the close job's trigger would stop firing, and no
 * couple's day would close, without a line in any log. That the application
 * turns virtual threads on (for its web server) is not something a day's
 * closing should rest on. A thread of this job's own makes it true whatever
 * the scheduler is, including one somebody configures later; a larger pool for
 * the scheduler would only have moved the number at which it stops being true.
 *
 * **While a tick is running the timer's ticks are skipped**, not queued: a
 * tick that takes long is followed by the next one within two seconds of its
 * end, never by a burst of the ticks it overran. The first skip of a run of
 * them is said at WARN, once, because a tick that outlasts its interval is
 * either a backlog or a handler that is stuck.
 *
 * **No `@SchedulerLock`, on purpose** (ADR-0035 decision 7). The close job takes one
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
 * **A tick goes round again while its passes fill their budget and more is
 * waiting, at most [MAX_PASSES] times.** A queue longer than one budget should
 * not sit for two seconds between budgets. The bound is there because a queue
 * can be fed as fast as it is drained, and a tick that looped until nothing
 * was waiting might never pause. Bounded, a tick always ends, the pause
 * follows, and the next tick takes up what is left.
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
 * by numbers this object keeps and overwrites after every tick, **including a
 * tick that threw**: the dispatcher failing on every tick is the poller stuck,
 * and that is when these numbers are read. The map holds
 * those numbers for the life of the job, which matters: a Micrometer gauge
 * keeps only a weak reference to what it reads, and a number nobody else held
 * would be collected and the gauge would read NaN.
 *
 * **A sixth meter says when the others were last true** (as the close job's
 * does, and for its reason: one cannot tell healthy from stopped without it).
 * When the backlog cannot be read, or a tick hangs and none follows, the
 * gauges keep their last values and look like a quiet queue;
 * [LAST_SUCCESS] stops moving, and that is what an alert can see.
 *
 * The two counters carry no consumer tag, because a run reports its totals
 * and not whose deliveries they were; the per-consumer picture is the gauges'.
 *
 * **A tick that delivered says so at DEBUG, and nothing at INFO.** The only
 * event there is to deliver is a withdrawal's. A line at INFO two seconds
 * after "A member ended bond …" would tell a reader of the log that this
 * ending took its entries back, and the two endings have opposite defaults,
 * so that it was probably a block (ADR-0028 decision 8). The close job
 * declines to report its own erasures for the same reason (ADR-0035 decision
 * 14). What an operator needs is still there: the counters and the gauges,
 * which name no bond, and every failure, which the dispatcher logs at WARN by
 * class name and this job logs at WARN when a tick ends with more waiting.
 */
@Component
internal class OutboxJob(
    private val dispatcher: OutboxDispatcher,
    private val clock: Clock,
    private val meters: MeterRegistry,
    /** What ticks are handed to. The application's is [ownThread]; a test hands in one that fails as a machine can. */
    private val worker: ExecutorService,
) : DisposableBean {
    /**
     * The constructor the application uses, and says so: with two, Spring
     * must be told which. The worker is this job's own and never a bean, so
     * that no executor somebody else declares can become the poller's.
     */
    @Autowired
    constructor(dispatcher: OutboxDispatcher, clock: Clock, meters: MeterRegistry) : this(dispatcher, clock, meters, ownThread())

    private val log = LoggerFactory.getLogger(javaClass)
    private val queues = ConcurrentHashMap<String, Queue>()
    private val lastSuccess = AtomicLong(0)
    private val tickRunning = AtomicBoolean(false)
    private val skipping = AtomicBoolean(false)

    @Volatile
    private var stopping = false
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

    init {
        Gauge
            .builder(LAST_SUCCESS) { lastSuccess.get().toDouble() }
            .description("Epoch seconds of the outbox poller's last tick that could read the backlog its gauges show")
            .register(meters)
    }

    /**
     * What the timer calls. It returns at once, whatever the tick does: the
     * thread it is called on is the scheduler's, and may be the only one the
     * close job has.
     *
     * **Whatever the hand-over throws, the tick is no longer "running".** A
     * tick that was never begun has no `finally` of its own to say it ended,
     * and an executor can refuse with more than a refusal: asked for a thread
     * the machine cannot give, it throws an `Error`. Were the flag left set,
     * every later call would be skipped as "the last tick is still running",
     * for the life of the process, with one line in the log to show for it.
     * So `Throwable`, as in [dispatchOnce], and named by its class for the
     * same reason: left to escape, the scheduler would print it whole.
     */
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedDelayString = "\${moyi.scheduling.outbox.delay:$EVERY_TWO_SECONDS}")
    fun run() {
        if (!tickRunning.compareAndSet(false, true)) {
            if (!skipping.getAndSet(true)) log.warn("outbox: the last tick is still running; none is started until it ends")
            return
        }
        try {
            worker.execute {
                try {
                    dispatchOnce()
                } finally {
                    skipping.set(false)
                    tickRunning.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            // The job is stopping, and a tick that will not run needs no line.
            tickRunning.set(false)
        } catch (thrown: Throwable) {
            // Never begun, so not running: see above.
            tickRunning.set(false)
            log.warn("outbox: a tick could not be handed over and the next one will try again: error={}", thrown.javaClass.name)
        }
    }

    /**
     * Stops the worker with the context, and before the connection pool goes:
     * no further pass is begun, the handler in flight is interrupted (its
     * delivery rolls back and stays due, which at-least-once allows), and the
     * tick is given a few seconds to end so that it ends against a database
     * that is still there. A handler that ignores the interrupt is left
     * behind; the thread is a daemon and holds nothing up.
     */
    override fun destroy() {
        stopping = true
        worker.shutdownNow()
        try {
            if (!worker.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("outbox: a tick was still running {} seconds after it was told to stop", STOP_WAIT_SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * One tick, on the caller's thread and over when it returns: what [run]
     * hands to the worker. Never throws.
     *
     * `Throwable`, on purpose: the dispatcher rethrows a `VirtualMachineError`
     * once it has recorded the delivery that raised it, and that too must be
     * named here rather than printed by whoever runs the thread.
     *
     * **Another pass only when the budget cut the last one short.** "More is
     * waiting" alone is not a reason: a pass that stopped under its budget ran
     * out of deliveries it could take, and what it reports as waiting is what
     * it set aside, a failure it could not record. Going round again would run
     * that handler again, as many times as a tick has passes, with no backoff.
     */
    @Suppress("TooGenericExceptionCaught")
    fun dispatchOnce() {
        try {
            var passes = 0
            var deliveredNow = 0
            var failedNow = 0
            var more: Boolean
            var again: Boolean
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
                val cutShort = result.delivered + result.failed >= BUDGET
                again = more && cutShort && passes < MAX_PASSES
            } while (again && !stopping)
            if (more) {
                // Still more when the tick ends: a backlog, or a delivery that cannot be set aside.
                log.warn("outbox: delivered {}, failed {}, and more is waiting after {} passes", deliveredNow, failedNow, passes)
            } else if (deliveredNow + failedNow > 0) {
                // DEBUG, not INFO: see the class's note on what a delivery says.
                log.debug("outbox: delivered {}, failed {}", deliveredNow, failedNow)
            }
        } catch (thrown: Throwable) {
            log.warn("outbox: the tick did not finish and the next one will try again: error={}", thrown.javaClass.name)
        } finally {
            // Whatever the tick met: a dispatcher that throws every time is when the gauges matter most.
            refreshMetersOrSayWhyNot()
        }
    }

    /**
     * A backlog that cannot be read leaves every gauge as it was, and
     * [LAST_SUCCESS] with them, which is how anyone can tell. By class name
     * alone, like everything else a tick meets.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun refreshMetersOrSayWhyNot() {
        try {
            refreshMeters()
        } catch (thrown: Throwable) {
            log.warn("outbox: the backlog could not be read, so the gauges are as the last tick left them: error={}", thrown.javaClass.name)
        }
    }

    /**
     * Sets every gauge from the backlog as it stands, and the time they were
     * set. A consumer the backlog has stopped reporting reads zero from then
     * on: its gauges cannot be left showing the last thing that was true of it.
     */
    fun refreshMeters() {
        val now = clock.instant()
        val backlog = dispatcher.backlog(now)
        backlog.forEach { queues.computeIfAbsent(it.consumerId, ::register).set(it) }
        val reported = backlog.mapTo(mutableSetOf()) { it.consumerId }
        queues.forEach { (consumerId, queue) -> if (consumerId !in reported) queue.clear() }
        lastSuccess.set(now.epochSecond)
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
        /** The one thread ticks run on. It is started by the first tick handed over, so a job nobody times never has one. */
        fun ownThread(): ExecutorService =
            Executors.newSingleThreadExecutor { tick -> Thread(tick, WORKER_THREAD).apply { isDaemon = true } }

        /**
         * The pause between ticks, unless `moyi.scheduling.outbox.delay` says
         * otherwise. Only `PollerIndependenceTest` sets it: two seconds is what the smoke run sees too.
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

        /**
         * `gratitude_outbox_last_success_timestamp` once exported. Not "the last tick that
         * delivered": the last one whose reading of the backlog is what the gauges now show.
         */
        const val LAST_SUCCESS = "gratitude.outbox.last.success.timestamp"

        const val WORKER_THREAD = "outbox-poller"

        /** How long [destroy] waits for the tick in flight before it leaves it behind. */
        const val STOP_WAIT_SECONDS = 5L
    }
}
