package com.moyi.gratitude.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.events.DispatchResult
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryEntity
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A handler that writes through JPA and **does not flush** has, when it
 * returns, told the database nothing. The dispatcher flushes for it, inside
 * the delivery's callback, before it lets the transaction commit
 * (`failNowWhatWouldFailAtCommit`).
 *
 * **Why that one line matters, and why nothing tested it.** Left to the
 * commit, the write is refused inside Spring's transaction manager, outside
 * anything the dispatcher can catch, and Spring logs that exception whole at
 * DEBUG; Postgres words a refused row by quoting it, entry text and all.
 * Every consumer in the build writes with `saveAndFlush`, so the line could
 * be deleted with every test still green. The `SET CONSTRAINTS` beside it
 * does not stand in for it: that is a JDBC statement, and JDBC does not make
 * Hibernate flush.
 *
 * So this context has a consumer of its own, [UnflushedWriter], which does
 * what a careless handler would. It subscribes to an event type nothing else
 * publishes, and is registered in the database the module's tests share:
 * one more row in `outbox_consumers`, which no other context has a bean for
 * and so never claims from.
 *
 * **What is looked for is Postgres's own wording, "Failing row", and not the
 * words.** With every logger at DEBUG, as here, Hibernate lists each managed
 * entity field by field at every flush (`org.hibernate.orm.core`, "Listing
 * entities"), an entry's text among them, whoever asked for the flush. That
 * is another channel, open wherever a live entry is flushed and nothing to
 * do with the dispatcher; searching for the words would fail on it with the
 * flush in place and so could not tell the two apart. It is recorded as a
 * finding of slice C5a, not settled here.
 *
 * Seen to fail with `status.flush()` removed from the dispatcher.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(UnflushedHandlerTest.Configuration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class UnflushedHandlerTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val writer: UnflushedWriter,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    @BeforeEach
    @AfterEach
    fun clear() {
        writer.work = {}
        rig.clear()
        users.clear()
    }

    @Test
    fun `a write the handler left unflushed is refused inside the delivery, and what the database says of the row reaches no log`() {
        val ada = users.verified("Ada")
        val bond = rig.pair(ada, users.verified("Bea"))
        val entry = UUID.fromString(rig.submit(ada, bond, WORDS))
        val before = rig.wholeEntries(bond)
        // Longer than `entries_text_octets_check` allows, and only set on the managed entity: no statement is sent.
        writer.work = { entityManager.find(EntryEntity::class.java, entry).text = WORDS + " and again".repeat(1_000) }
        val aggregate = UUID.randomUUID()
        transactions.executeWithoutResult {
            val references = mapOf("entryId" to entry)
            publisher.publish(OutboxEvent("UnflushedHandlerTest", aggregate, UnflushedWriter.EVENT, references, clock.instant()))
        }

        val said = everythingLoggedAtDebug { dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(0, 1, false) }

        writer.handled shouldBe 1
        rig.wholeEntries(bond) shouldBe before
        val failed =
            jdbc.queryForMap(
                """
                SELECT d.processed_at, d.attempts, d.last_error
                FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
                WHERE e.aggregate_id = ? AND d.consumer_id = ?
                """.trimIndent(),
                aggregate,
                UnflushedWriter.ID,
            )
        failed["processed_at"].shouldBeNull()
        failed["attempts"] shouldBe 1
        failed["last_error"].toString() shouldMatch Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+""")
        // The leak first, so that a failure here names the line that carried it.
        said.filter { it.contains("Failing row") }.map { it.take(200) }.shouldBeEmpty()
        // And not vacuous: the transaction machinery was heard at DEBUG while it failed.
        said.count { it.contains("org.springframework.orm.jpa.JpaTransactionManager") } shouldBeGreaterThan 0
    }

    /** As `WithdrawEntriesTest`'s: every line this JVM logs while [block] runs, with its exceptions' classes and messages. */
    private fun everythingLoggedAtDebug(block: () -> Unit): List<String> {
        val everything = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val level = root.level
        root.addAppender(everything)
        root.level = Level.DEBUG
        try {
            block()
        } finally {
            root.level = level
            root.detachAppender(everything)
        }
        return everything.list.toList().map { event ->
            "${event.loggerName} ${event.formattedMessage}" +
                generateSequence(event.throwableProxy) { it.cause }.joinToString("") { " ${it.className}: ${it.message}" }
        }
    }

    /** Does whatever the test last told it to, in the delivery's transaction, and flushes nothing. */
    class UnflushedWriter : EventConsumer {
        override val id = ID
        override val eventTypes = setOf(EVENT)
        override val startFrom = StartFrom.NOW

        @Volatile var work: () -> Unit = {}

        @Volatile var handled = 0

        override fun handle(event: ReceivedEvent) {
            handled++
            work()
        }

        companion object {
            const val ID = "gratitude.test.unflushed"
            const val EVENT = "UnflushedHandlerTestEvent"
        }
    }

    @TestConfiguration
    class Configuration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-15T10:00:00Z"))

        @Bean
        fun unflushedWriter(): UnflushedWriter = UnflushedWriter()
    }

    private companion object {
        /** Not spellable in hexadecimal, so no id a DEBUG line prints can be mistaken for it. */
        const val WORDS = "Thank you~ for the tea"
    }
}
