package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-060's acceptance clause. A failure here is a P0 (doc 11).
 *
 * **The whole of BR-1 is asserted here, not only what C1's own writes can
 * produce.** C1 opens days `OPEN` or `SUSPENDED`, moves them to `PARTIAL`,
 * and sets `revealed_at` and `deleted_at` nowhere — C2 reveals, C3 closes,
 * a later slice erases. The gate is nonetheless written for the rule as the
 * spec states it (§4), so the states those slices will produce are put in
 * place **by hand, with `UPDATE`**, and each test that does so says so: all
 * eight day statuses, a revealed entry, and an erased one in each order of
 * revealing and erasing. C2 and C3 replace the hand-made states with their
 * own transitions; neither may narrow what is asserted.
 *
 * `EntriesEndpointTest`'s own harness — a paired bond (`ada` and `bea`)
 * through the real `bond` endpoints [GratitudeTestApplication] scans for,
 * against a real Postgres, truncated between tests. The clock is pinned to
 * the middle of a Lagos day, so "today" is one fixed Bond-day for the whole
 * of every test and never the far side of a midnight.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class RevealGateTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW)
        ada = users.verified("Ada")
        bea = users.verified("Bea")

        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a locked entry is exactly an author and a status, and nothing else`() {
        val beaSubmitted = submit(bea, bondId, """{"text":"a secret kindness"}""")
        beaSubmitted.status shouldBe 201
        val beaMemberId = authorMemberIdOf(beaSubmitted)

        val today = getToday(ada, bondId)

        // Scoped to the partner's own object, not the whole body (fix round
        // 1, M2) — a whole-body check for `createdAt`/`length`/`hasImage`/
        // `intendedAt` would also pass once `ada` has an entry of her own
        // carrying those very field names, for a reason unrelated to the
        // gate. This pins `partnerEntry`'s exact wire shape: nothing but the
        // author and the status, in that order, and nothing else — BR-8,
        // byte for byte.
        partnerEntryJson(today.contentAsString) shouldBe """{"authorMemberId":"$beaMemberId","status":"LOCKED"}"""
        // The text itself, body-wide: it must never appear anywhere in the
        // response, not only be absent from partnerEntry's own shape.
        today.contentAsString shouldNotContain "a secret kindness"
    }

    @Test
    fun `the day's status IS returned, because it is deliberately shared`() {
        // Doc 04 §6.1: a member who has not written can infer from PARTIAL that
        // their partner has, and J2's "Waiting for Tunde" depends on it. The
        // converse assertion matters as much as the one above — a later change
        // that hid the status to be "safe" would break the product.
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201

        getToday(ada, bondId).contentAsString shouldContain "\"status\":\"PARTIAL\""
    }

    @Test
    fun `an author reads their own entry in full`() {
        submit(ada, bondId, """{"text":"thank you"}""").status shouldBe 201

        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"myEntry\""
        today.contentAsString shouldContain "thank you"
    }

    @Test
    fun `priming today as one member does not serve it to the other`() {
        // Doc 12 names cache poisoning as a reveal-gate bypass no
        // authorisation layer sees. `bea`'s own GET below is the priming
        // call that would matter: her response carries her own words under
        // `myEntry` (BR-1's first clause — an author always reads their own
        // in full), so a naive `bondId`-keyed cache that served that same
        // response back to `ada` would leak exactly what test 1 above
        // proves the reveal gate refuses her — a failure test 1 alone would
        // never catch, since it never primes anything. C1 caches nothing;
        // this test is what makes adding one later a deliberate act rather
        // than an accident.
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201
        getToday(bea, bondId).status shouldBe 200

        getToday(ada, bondId).contentAsString shouldNotContain "a secret kindness"
    }

    @Test
    fun `a day nobody has written to yet is OPEN with two absent entries`() {
        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"status\":\"OPEN\""
        today.contentAsString shouldContain "\"myEntry\":null"
        today.contentAsString shouldContain "\"partnerEntry\":null"
    }

    /**
     * BR-3/BR-3a's own guard, from the read side: C3 — the row [GetToday]
     * reports without ever creating one. Asserted here rather than only
     * inferred from the response, because a passing `OPEN` status is
     * consistent with either a row that does not exist or one that does and
     * merely has no entries yet — this is the assertion that tells them
     * apart.
     */
    @Test
    fun `a day nobody has written to yet is never created by reading it`() {
        getToday(ada, bondId).status shouldBe 200

        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 0
    }

    /**
     * Fix round 1, C1: `membership.awaitingSecondMember` (doc 04 §8.3a, J1)
     * was in hand at `GetToday` and unused — a still-solo bond's `/today`
     * reported `OPEN`, indistinguishable from a paired bond nobody has
     * written in, in exactly the field BR-1's own contract says is returned
     * *so that* the two can be told apart. This is also the regression
     * proof for the status-travels-backwards bug that omission caused:
     * `SubmitEntry` opens a still-solo bond's day `SUSPENDED`, so a creator
     * who had just been told `OPEN` would watch it become `SUSPENDED` on
     * their own write — an edge the state machine does not have. Asserted
     * both before and after the creator's own write, so the status is shown
     * not to move.
     */
    @Test
    fun `a still-solo bond reports SUSPENDED before anybody has written, not OPEN`() {
        val cara = users.verified("Cara")
        val solo = createBond(cara)
        val soloBondId = bondIdOf(solo)

        getToday(cara, soloBondId).contentAsString shouldContain "\"status\":\"SUSPENDED\""

        submit(cara, soloBondId, """{"text":"waiting for you"}""").status shouldBe 201

        getToday(cara, soloBondId).contentAsString shouldContain "\"status\":\"SUSPENDED\""
    }

    // ---- BR-1 keyed on the entry's own reveal (spec §4, as revised) ------
    //
    // C1 has no reveal and no close: C2 sets `entries.revealed_at`, C3 closes
    // a day. Every test below therefore puts the row into the state under
    // test BY HAND, with `UPDATE`, and says so — standing in for the slice
    // that will produce it. What is under test is the gate, not the
    // transition.
    //
    // Each test states what a broken gate would answer. The partner's entry
    // is always PRESENT in these responses — locked or in full, never
    // absent — so a gate that fails open shows the words rather than dropping
    // the field (the way the earlier C1 build's test passed for the wrong
    // reason).

    @Test
    fun `no day status reveals an unrevealed entry - not FROZEN, not REVEALED, not SOLO`() {
        // The gate keys on the entry's `revealedAt`, never on the day's
        // status. A gate keyed on status (the one this replaces) answers the
        // REVEALED and SOLO rows here in full; a gate that always answers
        // FULL answers every row in full. Either way `a secret kindness`
        // appears in the body and the exact-shape assertion fails.
        val beaMemberId = authorMemberIdOf(submit(bea, bondId, """{"text":"a secret kindness"}""").also { it.status shouldBe 201 })

        for (status in DAY_STATUSES) {
            setDayByHand(status)

            val today = getToday(ada, bondId)

            withClue(status) {
                today.status shouldBe 200
                today.contentAsString shouldContain "\"status\":\"$status\""
                partnerEntryJson(today.contentAsString) shouldBe """{"authorMemberId":"$beaMemberId","status":"LOCKED"}"""
                today.contentAsString shouldNotContain "a secret kindness"
            }
        }
    }

    @Test
    fun `the caller having written too does not reveal the partner's entry, under any day status`() {
        // The ordinary C1 state: both have written, nothing has been revealed
        // (C2 owns the reveal). A gate that reveals once both entries exist —
        // keyed on the day's entry count, say — answers Bea's entry in full
        // here, and both the exact-shape and the body-wide assertion see it.
        val beaMemberId = authorMemberIdOf(submit(bea, bondId, """{"text":"a secret kindness"}""").also { it.status shouldBe 201 })
        submit(ada, bondId, """{"text":"my own words"}""").status shouldBe 201

        for (status in DAY_STATUSES) {
            setDayByHand(status)

            val today = getToday(ada, bondId).contentAsString

            withClue(status) {
                partnerEntryJson(today) shouldBe """{"authorMemberId":"$beaMemberId","status":"LOCKED"}"""
                myEntryJson(today) shouldContain "\"text\":\"my own words\""
                today shouldNotContain "a secret kindness"
            }
        }
    }

    @Test
    fun `a revealed entry stays readable whatever the day becomes - frozen, suspended, anything`() {
        // `revealedAt` is monotonic: applying a freeze to a solo day, or
        // suspending the bond later, must not hide words the partner has
        // already read (spec §4). The status-keyed gate this replaces locks
        // the entry again on FROZEN, SUSPENDED, EMPTY, OPEN, PARTIAL and
        // PENDING_REVEAL; a gate that always answers LOCKED locks it on all
        // eight. Both fail the `text` assertion.
        submit(bea, bondId, """{"text":"a kindness you have read"}""").status shouldBe 201
        revealByHand(bea)

        for (status in DAY_STATUSES) {
            setDayByHand(status)

            val today = getToday(ada, bondId)

            withClue(status) {
                partnerEntryJson(today.contentAsString) shouldContain "\"text\":\"a kindness you have read\""
            }
        }
    }

    @Test
    fun `an author reads their own live entry whatever the day's status, SUSPENDED included`() {
        // Spec §4: "An author can still read their own live entry on a
        // SUSPENDED day." A gate that always answers LOCKED drops `myEntry`.
        submit(ada, bondId, """{"text":"my own words"}""").status shouldBe 201

        for (status in DAY_STATUSES) {
            setDayByHand(status)

            withClue(status) {
                myEntryJson(getToday(ada, bondId).contentAsString) shouldContain "\"text\":\"my own words\""
            }
        }
    }

    @Test
    fun `an author's withdrawn entry is the wide tombstone for them, whatever the erasure left behind`() {
        // Erasure beats authorship. The row's text is deliberately LEFT IN
        // PLACE in every state below, so the only thing between the words and
        // the wire is the gate — a gate without the tombstone clause hands
        // them back to their author and `shouldNotContain` fails.
        val submitted = submit(ada, bondId, """{"text":"words I took back"}""")
        submitted.status shouldBe 201

        for (state in ERASED_STATES) {
            jdbc.update("UPDATE entries SET $state WHERE author_member_id = ?::uuid", memberIdOf(ada)) shouldBe 1

            val asAuthor = getToday(ada, bondId).contentAsString

            withClue(state) {
                myEntryJson(asAuthor) shouldContain "\"id\":\"${idOf(submitted)}\""
                myEntryJson(asAuthor) shouldContain "\"text\":null"
                myEntryJson(asAuthor) shouldContain "\"status\":\"DELETED\""
                asAuthor shouldNotContain "words I took back"
            }
        }
    }

    @Test
    fun `a partner who never could read a withdrawn entry sees its author and that it is gone, and nothing else`() {
        // BR-8: while the entry was live this reader was entitled to author
        // and status. An erasure does not entitle them to more — not the id,
        // not when it was written. A gate that answers the wide tombstone
        // here fails the exact-equality assertion: the wide shape carries
        // `id`, `date`, `createdAt`, `intendedAt` and a `text` key.
        val adaMemberId = authorMemberIdOf(submit(ada, bondId, """{"text":"words I took back"}""").also { it.status shouldBe 201 })

        for (state in ERASED_STATES.filter { "revealed_at = NULL" in it }) {
            jdbc.update("UPDATE entries SET $state WHERE author_member_id = ?::uuid", memberIdOf(ada)) shouldBe 1

            // Before Bea has written, and after: her own entry changes nothing.
            for (beaHasWritten in listOf(false, true)) {
                if (beaHasWritten) submit(bea, bondId, """{"text":"bea's own"}""")

                val asPartner = getToday(bea, bondId).contentAsString

                withClue("$state, beaHasWritten=$beaHasWritten") {
                    partnerEntryJson(asPartner) shouldBe """{"authorMemberId":"$adaMemberId","status":"REMOVED"}"""
                    asPartner shouldNotContain "words I took back"
                }
            }
        }
    }

    @Test
    fun `a partner who had read a withdrawn entry gets the wide tombstone, without its words`() {
        // Revealed, then erased: this reader already knows the entry and
        // when it was written. Only the words go.
        val submitted = submit(ada, bondId, """{"text":"words I took back"}""")
        submitted.status shouldBe 201

        for (state in ERASED_STATES.filter { "revealed_at = now()" in it }) {
            jdbc.update("UPDATE entries SET $state WHERE author_member_id = ?::uuid", memberIdOf(ada)) shouldBe 1

            val asPartner = getToday(bea, bondId).contentAsString

            withClue(state) {
                partnerEntryJson(asPartner) shouldContain "\"id\":\"${idOf(submitted)}\""
                partnerEntryJson(asPartner) shouldContain "\"text\":null"
                partnerEntryJson(asPartner) shouldContain "\"status\":\"DELETED\""
                asPartner shouldNotContain "words I took back"
            }
        }
    }

    @Test
    fun `a non-member gets the one 404, whether or not the entry has been revealed`() {
        // Membership is checked FIRST: a revealed entry is not public. The
        // answer is the same 404 an unknown bond gets, before and after the
        // reveal — nothing about the entry's state reaches a non-member.
        submit(bea, bondId, """{"text":"revealed, not published"}""").status shouldBe 201
        val stranger = users.verified("Eve")

        for (revealed in listOf(false, true)) {
            if (revealed) {
                revealByHand(bea)
                setDayByHand("REVEALED")
            }

            val today = getToday(stranger, bondId)

            withClue("revealed=$revealed") {
                today.status shouldBe 404
                today.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
            }
        }
    }

    @Test
    fun `a replayed response goes through the same gate as today`() {
        // Spec §4: "The same gate applies to ... replayed responses, not just
        // today." The row is erased with only `deleted_at` set and its text
        // left in place: a replay that renders the entry without asking BR-1
        // (the shape this replaces looked at `status` alone) returns the words.
        val key = UUID.randomUUID().toString()
        submit(ada, bondId, """{"text":"said once"}""", key).status shouldBe 201
        jdbc.update("UPDATE entries SET deleted_at = now()")

        val replay = submit(ada, bondId, """{"text":"said once"}""", key)

        replay.status shouldBe 201
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldContain "\"text\":null"
        replay.contentAsString shouldContain "\"status\":\"DELETED\""
        replay.contentAsString shouldNotContain "said once"
    }

    @Test
    fun `a key answers only for the entry its own caller wrote, even one they could read`() {
        // The replay's author guard. Ada's key is pointed, by hand, at Bea's
        // entry — which has been revealed, so BR-1 alone would let Ada read
        // it. A replay is "what did MY request produce", and this is not it:
        // without the guard the gate answers FULL and the response is a 201
        // carrying Bea's words under Ada's key.
        val key = UUID.randomUUID().toString()
        submit(ada, bondId, """{"text":"said once"}""", key).status shouldBe 201
        val beas = submit(bea, bondId, """{"text":"bea's revealed words"}""")
        beas.status shouldBe 201
        revealByHand(bea)
        jdbc.update("UPDATE idempotency_keys SET result_id = ?::uuid WHERE user_id = ?", idOf(beas), ada) shouldBe 1

        val replay = submit(ada, bondId, """{"text":"said once"}""", key)

        replay.status shouldBe 404
        replay.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        replay.contentAsString shouldNotContain "bea's revealed words"
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER).shouldBeNull()
    }

    // ---- helpers --------------------------------------------------------

    /** C2's reveal, by hand: the entry's own timestamp and status, nothing else. */
    private fun revealByHand(author: UUID) {
        jdbc.update(
            "UPDATE entries SET revealed_at = now(), status = 'REVEALED' WHERE author_member_id = ?::uuid",
            memberIdOf(author),
        ) shouldBe 1
    }

    /** C2's and C3's day transitions, by hand: the status, and `closed_at` exactly where that status is a closed one. */
    private fun setDayByHand(status: String) {
        val closed = status in setOf("REVEALED", "SOLO", "EMPTY", "FROZEN")
        jdbc.update(
            "UPDATE bond_days SET status = ?, closed_at = CASE WHEN ? THEN now() ELSE NULL END WHERE bond_id = ?::uuid",
            status,
            closed,
            bondId,
        ) shouldBe 1
    }

    private fun memberIdOf(user: UUID): String =
        jdbc.queryForObject("SELECT id::text FROM bond_members WHERE bond_id = ?::uuid AND user_id = ?", String::class.java, bondId, user)!!

    private fun submit(
        caller: UUID,
        bondId: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun getToday(
        caller: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
            }.andReturn()
            .response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun authorMemberIdOf(response: MockHttpServletResponse): String =
        Regex(""""authorMemberId":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    /**
     * `partnerEntry`'s own JSON object, scoped out of the whole response body —
     * fix round 1, M2. Asserted present, with the body as the clue: a gate
     * that drops the field must fail as an assertion, not as an NPE.
     */
    private fun partnerEntryJson(body: String): String =
        withClue("partnerEntry is not an object in: $body") { PARTNER_ENTRY.find(body).shouldNotBeNull() }.groupValues[1]

    /** `myEntry`'s own JSON object, scoped the same way. */
    private fun myEntryJson(body: String): String =
        withClue("myEntry is not an object in: $body") { MY_ENTRY.find(body).shouldNotBeNull() }.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        val PARTNER_ENTRY = Regex(""""partnerEntry":(\{[^}]*})""")
        val MY_ENTRY = Regex(""""myEntry":(\{[^}]*})""")

        /** Doc 04 §3's eight, as V12's CHECK spells them. */
        val DAY_STATUSES = listOf("OPEN", "PARTIAL", "PENDING_REVEAL", "REVEALED", "SOLO", "EMPTY", "SUSPENDED", "FROZEN")

        /**
         * Every way a row can say it was erased, by hand (nothing in C1
         * erases). The text is left in place in each, so only the gate
         * stands between it and the wire. The first three were never
         * revealed; the last two were revealed first.
         */
        val ERASED_STATES =
            listOf(
                "status = 'DELETED', deleted_at = now(), revealed_at = NULL",
                "status = 'SUBMITTED', deleted_at = now(), revealed_at = NULL",
                "status = 'DELETED', deleted_at = NULL, revealed_at = NULL",
                "status = 'REVEALED', deleted_at = now(), revealed_at = now()",
                "status = 'DELETED', deleted_at = now(), revealed_at = now()",
            )

        /** 11:00 in Africa/Lagos on the 15th — nowhere near a day boundary. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
    }
}
