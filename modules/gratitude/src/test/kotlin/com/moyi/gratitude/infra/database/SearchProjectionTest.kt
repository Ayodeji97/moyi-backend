package com.moyi.gratitude.infra.database

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
 * `entries.text_search` and the vector V25 generates from it (ADR-0039 decisions 5 and 6):
 * written with an entry's words, rewritten with an edit, untouched by the reveal, and emptied
 * by every erasure there is — the author's delete, the withdrawal's consumer, and the close
 * job's pre-step, which erases a withdrawn author's entry before the consumer has run.
 *
 * Asked of the rows, not over HTTP: the gate hides an erased entry from every reader whatever
 * the row still holds, so only the row can show that the words have gone (BR-10).
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class SearchProjectionTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closeDay: CloseDay,
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
    fun `a submitted entry's words are its projection, and the vector folds case and accents`() {
        val bond = pair()
        val id = rig.submit(ada, bond, WORDS)

        projectionOf(id) shouldBe WORDS
        vectorOf(id) shouldBe "'acao':6 'cafe':3 'e':4 'obrigada':1 'pela':5 'pelo':2"
        finds(id, "cafe") shouldBe true
        finds(id, "CAFÉ") shouldBe true
        finds(id, "Ação obrigada") shouldBe true
        // Whole words only (the owner's Q-S4): no prefix.
        finds(id, "caf") shouldBe false
    }

    @Test
    fun `an edit before the reveal replaces the projection, and the reveal leaves it`() {
        val bond = pair()
        val id = rig.submit(ada, bond, WORDS)

        patch(ada, id, "thank you for the lamp")

        projectionOf(id) shouldBe "thank you for the lamp"
        finds(id, "cafe") shouldBe false
        finds(id, "lamp") shouldBe true
        rig.submit(bea, bond, "and for the bread")
        jdbc.queryForObject("SELECT revealed_at IS NOT NULL FROM entries WHERE id = ?::uuid", Boolean::class.java, id) shouldBe true
        projectionOf(id) shouldBe "thank you for the lamp"
    }

    @Test
    fun `the author's delete empties the projection and the vector`() {
        val bond = pair()
        val id = rig.submit(ada, bond, WORDS)

        rig.delete(ada, id)

        projectionOf(id).shouldBeNull()
        vectorOf(id) shouldBe ""
        finds(id, "cafe") shouldBe false
    }

    @Test
    fun `a withdrawal leaves the projection until its consumer runs, and the consumer empties it`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, WORDS)
        val beas = rig.submit(bea, bond, "and for the bread")

        rig.block(ada, bond)

        // ADR-0035's window: the marker hides the words from every reader, and the row still holds them.
        projectionOf(adas) shouldBe WORDS
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        projectionOf(adas).shouldBeNull()
        vectorOf(adas) shouldBe ""
        // The partner's words are not the withdrawal's.
        projectionOf(beas) shouldBe "and for the bread"
    }

    @Test
    fun `the close job's pre-step empties a withdrawn author's projection before the consumer has run`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, WORDS)
        rig.block(ada, bond)
        projectionOf(adas) shouldBe WORDS

        // The first instant after that Lagos day is over; the outbox is not dispatched.
        closeDay.settle(BondDayId(dayOf(bond, DAY)), Instant.parse("${DAY}T23:00:00Z"))

        projectionOf(adas).shouldBeNull()
        vectorOf(adas) shouldBe ""
    }

    // ---- helpers ----

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

    private fun vectorOf(entry: String): String =
        jdbc.queryForObject("SELECT search_vector::text FROM entries WHERE id = ?::uuid", String::class.java, entry)!!

    /** The query side exactly as Task 2's statement writes it. */
    private fun finds(
        entry: String,
        words: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT search_vector @@ plainto_tsquery('simple', public.immutable_unaccent(?)) FROM entries WHERE id = ?::uuid",
            Boolean::class.java,
            words,
            entry,
        )!!

    private fun dayOf(
        bond: String,
        date: LocalDate,
    ): UUID = jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid AND date = ?", UUID::class.java, bond, date)!!

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY: LocalDate = LocalDate.of(2026, 9, 15)
        const val WORDS = "Obrigada pelo Café e pela Ação"
        const val DISPATCH_BUDGET = 10
    }
}
