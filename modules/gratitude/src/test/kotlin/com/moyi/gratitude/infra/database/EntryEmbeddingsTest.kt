package com.moyi.gratitude.infra.database

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.common.events.DispatchResult
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.CloseDay
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * An entry's vectors (V41, ADR-0054): written only for the words the entry still holds, and
 * deleted, in the same transaction, whenever its `text_search` projection changes. An edit
 * and every erasure path ADR-0035 and ADR-0050 list (spec §3.4): the author's delete, the
 * withdrawal's consumer, the close job's pre-step, and a read's pre-step
 * (`EraseWithdrawnEntries` from `ReconcileJoiningDay`).
 *
 * Each erasure test writes a vector into **both** tables first: the deletion is one per table,
 * and a table it forgot would keep the words' meaning after the words were gone.
 *
 * Asked of the rows, as `SearchProjectionTest` asks of the projection: the gate hides an erased
 * entry whatever the row holds, so only the row can show the vector has gone.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class EntryEmbeddingsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closeDay: CloseDay,
    @Autowired private val embeddings: EntryEmbeddings,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `missing lists live entries with text and no vector, and upsert fills them`() {
        val bond = pair()
        val e = UUID.fromString(rig.submit(ada, bond, "thank you for the soup"))

        embeddings.missing(OPENAI, bond.uuid, 10).map { it.entryId } shouldBe listOf(e)
        embeddings.missing(OPENAI, bond.uuid, 10).single().text shouldBe "thank you for the soup"
        embeddings.upsert(OPENAI, e, bond.uuid, vec(1536), EntryEmbeddings.hashOf("thank you for the soup"), NOW) shouldBe true
        embeddings.missing(OPENAI, bond.uuid, 10) shouldBe emptyList()
        // The other table is still empty.
        embeddings.missing(VOYAGE, bond.uuid, 10).size shouldBe 1
        embeddings.countFor(bond.uuid) shouldBe 1
    }

    @Test
    fun `missing lists one bond's entries, oldest first, up to the limit, and never an erased one`() {
        val bond = pair()
        val other = pair()
        val first = rig.submit(ada, bond, "the first")
        clock.set(NOW.plusSeconds(60))
        val second = rig.submit(bea, bond, "the second")
        rig.submit(ada, other, "another bond's words")
        clock.set(NOW.plusSeconds(120))

        embeddings.missing(OPENAI, bond.uuid, 10).map { it.entryId.toString() } shouldBe listOf(first, second)
        embeddings.missing(OPENAI, bond.uuid, 1).map { it.entryId.toString() } shouldBe listOf(first)

        rig.delete(ada, first)
        embeddings.missing(OPENAI, bond.uuid, 10).map { it.entryId.toString() } shouldBe listOf(second)

        // An erased row whose projection was somehow left behind is still not embedded: either mark is enough.
        for (erasure in listOf("deleted_at = now()", "status = 'DELETED'")) {
            jdbc.update("UPDATE entries SET deleted_at = NULL, status = 'REVEALED' WHERE id = ?::uuid", second) shouldBe 1
            jdbc.update("UPDATE entries SET $erasure WHERE id = ?::uuid", second) shouldBe 1
            projectionOf(second) shouldBe "the second"
            embeddings.missing(OPENAI, bond.uuid, 10) shouldBe emptyList()
        }
    }

    @Test
    fun `upsert refuses a vector of words the entry no longer holds`() {
        val bond = pair()
        val e = UUID.fromString(rig.submit(ada, bond, "an old plum"))

        embeddings.upsert(OPENAI, e, bond.uuid, vec(1536), EntryEmbeddings.hashOf("a new pear"), NOW) shouldBe false
        embeddings.countFor(bond.uuid) shouldBe 0
    }

    @Test
    fun `upsert refuses an entry of another bond`() {
        val bond = pair()
        val other = pair()
        val e = UUID.fromString(rig.submit(ada, bond, "words"))

        embeddings.upsert(OPENAI, e, other.uuid, vec(1536), EntryEmbeddings.hashOf("words"), NOW) shouldBe false
        embeddings.countFor(bond.uuid) shouldBe 0
        embeddings.countFor(other.uuid) shouldBe 0
    }

    @Test
    fun `a second upsert replaces the row rather than adding one`() {
        val bond = pair()
        val e = UUID.fromString(rig.submit(ada, bond, "words"))

        embeddings.upsert(OPENAI, e, bond.uuid, vec(1536), EntryEmbeddings.hashOf("words"), NOW) shouldBe true
        embeddings.upsert(OPENAI, e, bond.uuid, vec(1536, 0.5f), EntryEmbeddings.hashOf("words"), NOW) shouldBe true

        embeddings.countFor(bond.uuid) shouldBe 1
        jdbc.queryForObject(
            "SELECT (embedding::real[])[1] FROM entry_embeddings_openai_1536 WHERE entry_id = ?",
            Float::class.java,
            e,
        ) shouldBe 0.5f
    }

    /** Review focus 2: edited from plum to pear, the entry must not match "plum" by meaning before the sweep runs again. */
    @Test
    fun `an edit deletes the vector, so the old words are never matched by meaning`() {
        val bond = pair()
        val id = rig.submit(ada, bond, "an old plum")
        embedBoth(id, bond, "an old plum")

        patch(ada, id, "a new pear")

        embeddings.countFor(bond.uuid) shouldBe 0
        // A vector of the old words still in flight (read before the edit) is refused.
        embeddings.upsert(OPENAI, id.uuid, bond.uuid, vec(1536), EntryEmbeddings.hashOf("an old plum"), NOW) shouldBe false
        // And the sweep sees the entry again, with its new words.
        embeddings.missing(OPENAI, bond.uuid, 10).single().text shouldBe "a new pear"
    }

    @Test
    fun `the author's delete deletes the vector`() {
        val bond = pair()
        val id = rig.submit(ada, bond, WORDS)
        embedBoth(id, bond, WORDS)

        rig.delete(ada, id)

        projectionOf(id).shouldBeNull()
        embeddings.countFor(bond.uuid) shouldBe 0
    }

    @Test
    fun `the withdrawal consumer's erasure deletes the vector`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, WORDS)
        val beas = rig.submit(bea, bond, "and for the bread")
        embedBoth(adas, bond, WORDS)
        embedBoth(beas, bond, "and for the bread")

        rig.block(ada, bond)
        // ADR-0035's window: nothing has erased yet.
        embeddings.countFor(bond.uuid) shouldBe 4
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)

        projectionOf(adas).shouldBeNull()
        // The partner's words, and so their vectors, are not the withdrawal's.
        embeddings.countFor(bond.uuid) shouldBe 2
        vectorsOf(adas) shouldBe 0
        vectorsOf(beas) shouldBe 2
    }

    @Test
    fun `the close job's pre-step erasure deletes the vector`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, WORDS)
        embedBoth(adas, bond, WORDS)
        rig.block(ada, bond)

        // The first instant after that Lagos day is over; the outbox is not dispatched.
        closeDay.settle(BondDayId(dayOf(bond, DAY)), Instant.parse("${DAY}T23:00:00Z"))

        rig.delivery(bond)["processed_at"].shouldBeNull()
        projectionOf(adas).shouldBeNull()
        embeddings.countFor(bond.uuid) shouldBe 0
    }

    /**
     * ADR-0050's fourth path: the first read to meet a suspended joining day erases a withdrawn
     * author's entry on it (`ReconcileJoiningDay` → `EraseWithdrawnEntries`), before the consumer
     * has run. The day is the one C1 left, made by hand as `WithdrawalRaceTest` makes it.
     */
    @Test
    fun `a read's withdrawal pre-step erasure deletes the vector`() {
        val created = rig.create(ada)
        val bond = rig.idOf(created)
        val adas = rig.submit(ada, bond, WORDS)
        rig.accept(bea, rig.codeOf(created))
        val beas = rig.submit(bea, bond, "and for the bread")
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 1
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 2
        embedBoth(adas, bond, WORDS)
        embedBoth(beas, bond, "and for the bread")
        rig.block(ada, bond)
        rig.postpone(bond, NOW.plusSeconds(86_400))

        rig.today(bea, bond)

        rig.delivery(bond)["processed_at"].shouldBeNull()
        projectionOf(adas).shouldBeNull()
        vectorsOf(adas) shouldBe 0
        vectorsOf(beas) shouldBe 2
    }

    @Test
    fun `an update that leaves the words alone keeps the vector`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, WORDS)
        embedBoth(adas, bond, WORDS)

        // Bea's entry reveals the day: Ada's row is written again, its words unchanged.
        rig.submit(bea, bond, "and for the bread")

        jdbc.queryForObject("SELECT revealed_at IS NOT NULL FROM entries WHERE id = ?::uuid", Boolean::class.java, adas) shouldBe true
        vectorsOf(adas) shouldBe 2
    }

    @Test
    fun `deleteForBond empties the bond's rows in every table and no other bond's`() {
        val bond = pair()
        val other = pair()
        val mine = rig.submit(ada, bond, WORDS)
        val theirs = rig.submit(ada, other, WORDS)
        embedBoth(mine, bond, WORDS)
        embedBoth(theirs, other, WORDS)

        embeddings.deleteForBond(bond.uuid) shouldBe 2

        embeddings.countFor(bond.uuid) shouldBe 0
        embeddings.countFor(other.uuid) shouldBe 2
    }

    @Test
    fun `each space has its own table, and a pending entry never prints its words`() {
        EmbeddingSpace.entries.map { EntryEmbeddings.tableOf(it) } shouldBe
            listOf("entry_embeddings_openai_1536", "entry_embeddings_voyage_1024")
        EntryEmbeddings.Pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "secret words").toString() shouldNotContain
            "secret"
    }

    // ---- helpers ----

    private val String.uuid: UUID get() = UUID.fromString(this)

    private fun embedBoth(
        entry: String,
        bond: String,
        words: String,
    ) {
        EmbeddingSpace.entries.forEach { space ->
            embeddings.upsert(space, entry.uuid, bond.uuid, vec(space.dimensions), EntryEmbeddings.hashOf(words), NOW) shouldBe true
        }
    }

    private fun vec(
        dimensions: Int,
        first: Float = 0.25f,
    ): FloatArray = FloatArray(dimensions) { if (it == 0) first else 0.001f * (it % 7) }

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.pair(ada, bea).also { clock.set(NOW) }
    }

    private fun patch(
        user: UUID,
        id: String,
        words: String,
    ) {
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun projectionOf(entry: String): String? =
        jdbc.queryForObject("SELECT text_search FROM entries WHERE id = ?::uuid", String::class.java, entry)

    /** Rows for [entry] across every table. */
    private fun vectorsOf(entry: String): Int =
        EmbeddingSpace.entries.sumOf { space ->
            jdbc.queryForObject(
                "SELECT count(*) FROM ${EntryEmbeddings.tableOf(space)} WHERE entry_id = ?::uuid",
                Int::class.java,
                entry,
            )!!
        }

    private fun dayOf(
        bond: String,
        date: LocalDate,
    ): UUID = jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid AND date = ?", UUID::class.java, bond, date)!!

    private companion object {
        val OPENAI = EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL
        val VOYAGE = EmbeddingSpace.VOYAGE_3_5
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY: LocalDate = LocalDate.of(2026, 9, 15)
        const val WORDS = "thank you for the soup"
        const val DISPATCH_BUDGET = 10
    }
}
