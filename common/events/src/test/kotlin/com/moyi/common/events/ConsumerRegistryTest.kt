package com.moyi.common.events

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Real-Postgres proof of what registration writes, and of the one thing it
 * exists to guarantee: a consumer that registers while events are being
 * published misses none of them (ADR-0035 decision 2).
 *
 * Every test registers under a consumer id and event types of its own, and
 * asserts on those rows only: the database is shared with whatever else ran
 * in this JVM.
 */
@SpringBootTest(classes = [EventsTestApplication::class])
internal class ConsumerRegistryTest(
    @Autowired private val publisher: EventPublisher,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val manager: PlatformTransactionManager,
    @Autowired private val dataSource: DataSource,
    @Autowired private val bound: RegistrationProperties,
) : PostgresIntegrationTest() {
    private val transactions = TransactionTemplate(manager)
    private val clock = MutableClock(Instant.parse("2026-10-06T12:00:00.123456Z"))

    private val consumerId = "test.registry.${UUID.randomUUID()}"
    private val typeA = "RegistryTestA.${UUID.randomUUID()}"
    private val typeB = "RegistryTestB.${UUID.randomUUID()}"

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_subscriptions, outbox_consumers, outbox_events")
    }

    @Test
    fun `a new consumer is recorded, with one subscription for each type it declares`() {
        registryOf(consumer(setOf(typeA, typeB))).afterSingletonsInstantiated()

        registeredAt() shouldBe clock.instant()
        subscriptions() shouldContainExactlyInAnyOrder listOf(typeA to clock.instant(), typeB to clock.instant())
    }

    @Test
    fun `a consumer starting from the beginning is owed what was published before it existed, due at once`() {
        val earlier = publish(typeA)
        val notItsType = publish(typeB)

        registryOf(consumer(setOf(typeA), StartFrom.BEGINNING)).afterSingletonsInstantiated()

        deliveredEvents() shouldContainExactly listOf(earlier)
        val delivery = jdbc.queryForMap("SELECT * FROM outbox_deliveries WHERE event_id = ? AND consumer_id = ?", earlier, consumerId)
        (delivery["next_attempt_at"] as Timestamp).toInstant() shouldBe clock.instant()
        delivery["processed_at"].shouldBeNull()
        delivery["attempts"] shouldBe 0
        deliveriesOf(notItsType).shouldBeEmpty()
    }

    @Test
    fun `a consumer starting from now is owed only what is published after it registered`() {
        val earlier = publish(typeA)

        registryOf(consumer(setOf(typeA), StartFrom.NOW)).afterSingletonsInstantiated()
        val later = publish(typeA)

        deliveriesOf(earlier).shouldBeEmpty()
        deliveredEvents() shouldContainExactly listOf(later)
    }

    @Test
    fun `registering again writes nothing new, whatever the consumer now says about where it starts`() {
        val earlier = publish(typeA)
        registryOf(consumer(setOf(typeA), StartFrom.NOW)).afterSingletonsInstantiated()
        val first = clock.instant()
        val later = publish(typeA)

        // A restart, a day on, of a build that changed its mind about the start:
        // the subscription exists, so there is no backfill to run a second time.
        clock.advance(Duration.ofDays(1))
        registryOf(consumer(setOf(typeA), StartFrom.BEGINNING)).afterSingletonsInstantiated()

        registeredAt() shouldBe first
        subscriptions() shouldContainExactly listOf(typeA to first)
        deliveriesOf(earlier).shouldBeEmpty()
        deliveredEvents() shouldContainExactly listOf(later)
    }

    @Test
    fun `a type the consumer no longer declares loses its subscription and keeps its deliveries`() {
        registryOf(consumer(setOf(typeA, typeB))).afterSingletonsInstantiated()
        val a = publish(typeA)
        val b = publish(typeB)

        registryOf(consumer(setOf(typeA))).afterSingletonsInstantiated()
        val unsubscribed = publish(typeB)

        subscriptions().map { it.first } shouldContainExactly listOf(typeA)
        deliveredEvents() shouldContainExactlyInAnyOrder listOf(a, b)
        deliveriesOf(unsubscribed).shouldBeEmpty()
    }

    @Test
    fun `a type added later is backfilled on its own, leaving the types already subscribed alone`() {
        val beforeAnything = publish(typeA)
        registryOf(consumer(setOf(typeA), StartFrom.NOW)).afterSingletonsInstantiated()
        val b = publish(typeB)

        registryOf(consumer(setOf(typeA, typeB), StartFrom.BEGINNING)).afterSingletonsInstantiated()

        deliveredEvents() shouldContainExactly listOf(b)
        deliveriesOf(beforeAnything).shouldBeEmpty()
    }

    @Test
    fun `two consumers under one id refuse to start`() {
        shouldThrow<IllegalStateException> {
            registryOf(consumer(setOf(typeA)), consumer(setOf(typeB)))
        }.message shouldBe "Two event consumers share the id '$consumerId'; delivery state is keyed by it, so it must be unique."
    }

    @Test
    fun `the registry answers with the consumer registered under an id, and with nothing for an id it does not know`() {
        val consumer = consumer(setOf(typeA))
        val registry = registryOf(consumer)

        registry.consumer(consumerId) shouldBe consumer
        registry.consumer("test.registry.unknown").shouldBeNull()
    }

    @Test
    fun `a delivery cannot be owed to a consumer nobody registered`() {
        // V20's foreign key: such a row could never be claimed, and would sit in
        // the pending count of a consumer that does not exist.
        val event = publish(typeA)

        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at) VALUES (?, ?, now())", event, consumerId)
        }
    }

    @Test
    fun `an event published but not yet committed when a consumer registers is still delivered to it`() {
        // The publisher has inserted its event and read the subscriptions (there
        // were none) but has not committed. A registration that did not wait
        // would backfill from a snapshot the event is not in, and the publisher
        // would never write the delivery: the event would be lost to this
        // consumer for ever. Remove the LOCK TABLE and this fails.
        val event = event(typeA)
        val published = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val publishing =
                pool.submit {
                    transactions.executeWithoutResult {
                        publisher.publish(event)
                        published.countDown()
                        check(commit.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the publisher" }
                    }
                }
            published.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val registering = pool.submit { registryOf(consumer(setOf(typeA), StartFrom.BEGINNING)).afterSingletonsInstantiated() }
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { registering.isDone || waitingOnOutboxEvents("ShareRowExclusiveLock") }
            registering.isDone shouldBe false

            commit.countDown()
            publishing.get(WAIT_SECONDS, TimeUnit.SECONDS)
            registering.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            commit.countDown()
            pool.shutdownNow()
        }

        deliveredEvents() shouldContainExactly listOf(idOf(event))
    }

    @Test
    fun `an event published while a consumer is registering waits, and is delivered to it`() {
        // The mirror: registration holds the lock, uncommitted. The consumer
        // starts from NOW, so the delivery asserted below cannot be a backfill;
        // only the publisher, reading the subscription after it was let through,
        // can have written it.
        val consumer = consumer(setOf(typeA), StartFrom.NOW)
        val registry = registryOf(consumer)
        val event = event(typeA)
        val registered = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val registering =
                pool.submit {
                    transactions.executeWithoutResult {
                        registry.register(consumer)
                        registered.countDown()
                        check(commit.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the registration" }
                    }
                }
            registered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val publishing = pool.submit { transactions.executeWithoutResult { publisher.publish(event) } }
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { publishing.isDone || waitingOnOutboxEvents("RowExclusiveLock") }
            publishing.isDone shouldBe false

            commit.countDown()
            registering.get(WAIT_SECONDS, TimeUnit.SECONDS)
            publishing.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            commit.countDown()
            pool.shutdownNow()
        }

        deliveredEvents() shouldContainExactly listOf(idOf(event))
    }

    @Test
    fun `at start-up the lock is held until what it guards has been written`() {
        // The path the application takes: `afterSingletonsInstantiated`, with
        // its own transaction. The consumer's event types are first read inside
        // `register`, after the lock, so holding that read holds registration
        // between taking the lock and writing the subscription. A publisher
        // arriving then must wait. Were the lock taken in one transaction and
        // the writes made in another, it would walk through, read no
        // subscription, and its event would never be owed to this consumer:
        // it starts from NOW, so no backfill could put that right.
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumer =
            object : EventConsumer {
                override val id = consumerId
                override val startFrom = StartFrom.NOW
                override val eventTypes: Set<String>
                    get() {
                        inside.countDown()
                        check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the registration" }
                        return setOf(typeA)
                    }

                override fun handle(event: ReceivedEvent) = error("registration never delivers")
            }
        val registry = registryOf(consumer)
        val event = event(typeA)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val registering = pool.submit { registry.afterSingletonsInstantiated() }
            inside.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val publishing = pool.submit { transactions.executeWithoutResult { publisher.publish(event) } }
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { publishing.isDone || waitingOnOutboxEvents("RowExclusiveLock") }
            publishing.isDone shouldBe false

            release.countDown()
            registering.get(WAIT_SECONDS, TimeUnit.SECONDS)
            publishing.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }

        deliveredEvents() shouldContainExactly listOf(idOf(event))
    }

    @Test
    fun `a registration that cannot have its lock in time fails, registers nothing, and lets the publishers behind it go`() {
        // A session has published and will not commit. Registration queues for
        // its lock behind that session, and a second publisher (which the first
        // would not have blocked) queues behind registration. Unbounded, that
        // is a start-up that hangs and a service that cannot write. Bounded,
        // registration gives up, the application does not start, and the
        // second publisher goes through while the first is still open.
        val stuck = event(typeA)
        val queued = event(typeA)
        val published = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        onOneConnection { single, singleJdbc ->
            val sessionDefault = lockTimeoutOf(singleJdbc)
            val registry =
                ConsumerRegistry(
                    listOf(consumer(setOf(typeA), StartFrom.BEGINNING)),
                    singleJdbc,
                    DataSourceTransactionManager(single),
                    clock,
                    RegistrationProperties(lockTimeout = Duration.ofSeconds(2)),
                )
            try {
                val holding =
                    pool.submit {
                        transactions.executeWithoutResult {
                            publisher.publish(stuck)
                            published.countDown()
                            check(commit.await(2 * WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the publisher" }
                        }
                    }
                published.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

                val registering = pool.submit { registry.afterSingletonsInstantiated() }
                await()
                    .atMost(Duration.ofSeconds(WAIT_SECONDS))
                    .until { registering.isDone || waitingOnOutboxEvents("ShareRowExclusiveLock") }
                registering.isDone shouldBe false

                val publishing = pool.submit { transactions.executeWithoutResult { publisher.publish(queued) } }
                await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { publishing.isDone || waitingOnOutboxEvents("RowExclusiveLock") }
                publishing.isDone shouldBe false

                // Within the bound: this `get` waits less than the publisher is held for.
                val refused = shouldThrow<ExecutionException> { registering.get(WAIT_SECONDS, TimeUnit.SECONDS) }.cause
                refused.shouldBeInstanceOf<IllegalStateException>()
                refused.message shouldContain consumerId
                refused.message shouldContain "PT2S"
                // What it knows is that the lock was not granted; who held it, it cannot see, and
                // an uncommitted publisher is one of several. It names them all and accuses none.
                refused.message shouldContain "Some other session holds a lock on that table"
                refused.message shouldContain "another instance registering"
                refused.message shouldNotContain "is holding it"
                // Postgres' own "lock not available", so no other failure is mistaken for this one.
                causes(refused).filterIsInstance<SQLException>().map { it.sqlState } shouldContainExactly listOf("55P03")

                // The publisher that was queued proceeds, with the first one still uncommitted.
                publishing.get(WAIT_SECONDS, TimeUnit.SECONDS)
                holding.isDone shouldBe false
                commit.countDown()
                holding.get(WAIT_SECONDS, TimeUnit.SECONDS)
            } finally {
                commit.countDown()
                pool.shutdownNow()
            }

            jdbc.queryForObject("SELECT count(*) FROM outbox_consumers WHERE consumer_id = ?", Int::class.java, consumerId) shouldBe 0
            subscriptions().shouldBeEmpty()
            deliveredEvents().shouldBeEmpty()
            // The bound was this transaction's, and went with it.
            lockTimeoutOf(singleJdbc) shouldBe sessionDefault
        }
    }

    @Test
    fun `the bound on the lock is set for the registration's transaction and is gone from the session after it`() {
        // One connection throughout, so that "after" is asked of the very
        // session registration ran on: in a pool it would be asked of another.
        onOneConnection { single, singleJdbc ->
            val sessionDefault = lockTimeoutOf(singleJdbc)
            var during: String? = null
            val consumer =
                object : EventConsumer {
                    override val id = consumerId
                    override val startFrom = StartFrom.NOW
                    override val eventTypes: Set<String>
                        get() {
                            // Read inside `register`, so on its transaction.
                            during = lockTimeoutOf(singleJdbc)
                            return setOf(typeA)
                        }

                    override fun handle(event: ReceivedEvent) = error("registration never delivers")
                }
            val properties = RegistrationProperties(lockTimeout = Duration.ofMillis(7_500))

            ConsumerRegistry(listOf(consumer), singleJdbc, DataSourceTransactionManager(single), clock, properties)
                .afterSingletonsInstantiated()

            during shouldBe "7500ms"
            lockTimeoutOf(singleJdbc) shouldBe sessionDefault
            subscriptions().map { it.first } shouldContainExactly listOf(typeA)
        }
    }

    @Test
    fun `the bound is ten seconds unless a property says otherwise, and can never be none at all`() {
        // The context sets nothing, so this is the default every context gets.
        bound.lockTimeout shouldBe Duration.ofSeconds(10)

        ApplicationContextRunner()
            .withUserConfiguration(EventsConfiguration::class.java)
            .withPropertyValues("moyi.events.registration.lock-timeout=3s")
            .run { it.getBean(RegistrationProperties::class.java).lockTimeout shouldBe Duration.ofSeconds(3) }

        // To Postgres a lock_timeout of zero means "wait for ever".
        shouldThrow<IllegalArgumentException> { RegistrationProperties(lockTimeout = Duration.ZERO) }
    }

    @Test
    fun `each registration says in one line who registered, the types that were new, and how much was backfilled`() {
        val logger = LoggerFactory.getLogger(ConsumerRegistry::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            publish(typeA)
            publish(typeA)
            publish(typeB)

            registryOf(consumer(setOf(typeA, typeB), StartFrom.BEGINNING)).afterSingletonsInstantiated()
            // A restart: nothing is new, and the line says so.
            registryOf(consumer(setOf(typeA, typeB), StartFrom.BEGINNING)).afterSingletonsInstantiated()
        } finally {
            logger.detachAppender(appender)
        }

        appender.list.map { it.level } shouldContainExactly listOf(Level.INFO, Level.INFO)
        val (first, restart) = appender.list.map { it.formattedMessage }
        first shouldContain "consumer=$consumerId"
        first shouldContain "subscribed=${listOf(typeA, typeB).sorted()}"
        first shouldContain "backfilled=3"
        restart shouldContain "consumer=$consumerId"
        restart shouldContain "subscribed=[]"
        restart shouldContain "backfilled=0"
    }

    @Test
    fun `a clock finer than Postgres keeps is truncated in everything registration writes`() {
        // Postgres rounds: ...123456789 would come back as ...123457.
        publish(typeA)
        val fine = Clock.fixed(Instant.parse("2026-10-06T12:00:00.123456789Z"), ZoneOffset.UTC)

        ConsumerRegistry(listOf(consumer(setOf(typeA), StartFrom.BEGINNING)), jdbc, manager, fine, bound).afterSingletonsInstantiated()

        val truncated = Instant.parse("2026-10-06T12:00:00.123456Z")
        registeredAt() shouldBe truncated
        subscriptions() shouldContainExactly listOf(typeA to truncated)
        jdbc
            .queryForObject("SELECT next_attempt_at FROM outbox_deliveries WHERE consumer_id = ?", Timestamp::class.java, consumerId)!!
            .toInstant() shouldBe truncated
    }

    /** Runs [block] with a data source that is one connection and nothing else, and gives the connection back. */
    private fun onOneConnection(block: (SingleConnectionDataSource, JdbcTemplate) -> Unit) {
        dataSource.connection.use { connection ->
            val single = SingleConnectionDataSource(connection, true)
            block(single, JdbcTemplate(single))
        }
    }

    private fun lockTimeoutOf(on: JdbcTemplate): String = on.queryForObject("SHOW lock_timeout", String::class.java)!!

    private fun causes(of: Throwable): List<Throwable> = generateSequence(of) { it.cause }.toList()

    /** Whether some session is queued, ungranted, for a lock of [mode] on `outbox_events`. */
    private fun waitingOnOutboxEvents(mode: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pg_locks
            WHERE NOT granted AND locktype = 'relation' AND relation = 'outbox_events'::regclass AND mode = ?
            """.trimIndent(),
            Int::class.java,
            mode,
        )!! > 0

    private fun registryOf(vararg consumers: EventConsumer) = ConsumerRegistry(consumers.toList(), jdbc, manager, clock, bound)

    private fun consumer(
        eventTypes: Set<String>,
        startFrom: StartFrom = StartFrom.BEGINNING,
    ): EventConsumer =
        object : EventConsumer {
            override val id = consumerId
            override val eventTypes = eventTypes
            override val startFrom = startFrom

            override fun handle(event: ReceivedEvent) = error("registration never delivers")
        }

    private fun event(type: String) =
        OutboxEvent("RegistryTest", UUID.randomUUID(), type, mapOf("bondId" to UUID.randomUUID()), clock.instant())

    private fun publish(type: String): UUID {
        val event = event(type)
        transactions.executeWithoutResult { publisher.publish(event) }
        return idOf(event)
    }

    private fun idOf(event: OutboxEvent): UUID =
        jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID::class.java, event.aggregateId)!!

    private fun registeredAt(): Instant =
        jdbc
            .queryForObject("SELECT registered_at FROM outbox_consumers WHERE consumer_id = ?", Timestamp::class.java, consumerId)!!
            .toInstant()

    private fun subscriptions(): List<Pair<String, Instant>> =
        jdbc.query(
            "SELECT event_type, subscribed_at FROM outbox_subscriptions WHERE consumer_id = ? ORDER BY event_type",
            { row, _ -> row.getString("event_type") to row.getTimestamp("subscribed_at").toInstant() },
            consumerId,
        )

    /** The events this test's consumer has a delivery for. */
    private fun deliveredEvents(): List<UUID> =
        jdbc.queryForList("SELECT event_id FROM outbox_deliveries WHERE consumer_id = ?", UUID::class.java, consumerId).filterNotNull()

    private fun deliveriesOf(eventId: UUID): List<String> =
        jdbc.queryForList("SELECT consumer_id FROM outbox_deliveries WHERE event_id = ?", String::class.java, eventId).filterNotNull()

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
