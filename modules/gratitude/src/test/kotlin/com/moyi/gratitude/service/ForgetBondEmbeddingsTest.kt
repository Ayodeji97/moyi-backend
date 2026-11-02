package com.moyi.gratitude.service

import com.moyi.common.events.DispatchResult
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.EmbeddingSweep
import com.moyi.gratitude.infra.FakeEmbeddingPort
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryEmbeddings
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `gratitude.semantic-search-off` (ADR-0054, spec §3.4): turning semantic search off deletes
 * the bond's vectors in every table, through the outbox, and only that bond's.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class ForgetBondEmbeddingsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val sweep: EmbeddingSweep,
    @Autowired private val fake: FakeEmbeddingPort,
    @Autowired private val embeddings: EntryEmbeddings,
    @Autowired private val forget: ForgetBondEmbeddings,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.clear()
        fake.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.clear()
        fake.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `SemanticSearchDisabled deletes the bond's vectors in every table, and only that bond's`() {
        val a = pair()
        val b = pair()
        val mine = rig.submit(ada, a, "x")
        rig.submit(ada, b, "y")
        sweep.embedPending(10).embedded shouldBe 2
        // A vector in the other space too: the delete is one per table.
        fake.space = VOYAGE
        sweep.embedPending(10).embedded shouldBe 2
        vectorsOf(mine) shouldBe 2

        turnOff(a)
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)

        embeddings.countFor(a.uuid) shouldBe 0
        embeddings.countFor(b.uuid) shouldBe 2
    }

    @Test
    fun `a second delivery of the same event is harmless`() {
        val a = pair()
        rig.submit(ada, a, "x")
        sweep.embedPending(10)
        val event = eventFor(a.uuid)

        transactions.executeWithoutResult { forget.handle(event) }
        transactions.executeWithoutResult { forget.handle(event) }

        embeddings.countFor(a.uuid) shouldBe 0
    }

    @Test
    fun `an event for a bond with no vectors, or no such bond, is a no-op that returns normally`() {
        val other = pair()
        rig.submit(ada, other, "y")
        sweep.embedPending(10)
        val empty = pair()

        transactions.executeWithoutResult { forget.handle(eventFor(empty.uuid)) }
        transactions.executeWithoutResult { forget.handle(eventFor(UUID.randomUUID())) }

        embeddings.countFor(other.uuid) shouldBe 1
    }

    @Test
    fun `an event without a bond is a fault, retried rather than passed over`() {
        shouldThrow<IllegalStateException> {
            transactions.executeWithoutResult { forget.handle(eventFor(UUID.randomUUID()).copy(references = emptyMap())) }
        }
    }

    @Test
    fun `it runs only in the delivery's transaction`() {
        shouldThrow<IllegalTransactionStateException> { forget.handle(eventFor(UUID.randomUUID())) }
    }

    /** Pre-flight F9: an erasure is owed for an event published before this consumer first registered. */
    @Test
    fun `it is owed every event since the beginning, under its own id`() {
        forget.id shouldBe "gratitude.semantic-search-off"
        forget.eventTypes shouldBe setOf("SemanticSearchDisabled")
        forget.startFrom shouldBe StartFrom.BEGINNING
    }

    // ---- helpers ----

    private val String.uuid: UUID get() = UUID.fromString(this)

    private fun eventFor(bondId: UUID) =
        ReceivedEvent(
            id = UUID.randomUUID(),
            aggregateType = "Bond",
            aggregateId = bondId,
            eventType = "SemanticSearchDisabled",
            references = mapOf("bondId" to bondId),
            occurredAt = NOW,
        )

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.pair(ada, bea).also { clock.set(NOW) }
    }

    private fun turnOff(bond: String) = rig.turnOffSemanticSearch(ada, bond)

    private fun vectorsOf(entry: String): Int =
        listOf("entry_embeddings_openai_1536", "entry_embeddings_voyage_1024").sumOf { table ->
            jdbc.queryForObject("SELECT count(*) FROM $table WHERE entry_id = ?::uuid", Int::class.java, entry)!!
        }

    private companion object {
        val VOYAGE = com.moyi.ai.api.EmbeddingSpace.VOYAGE_3_5
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val DISPATCH_BUDGET = 10
    }
}
