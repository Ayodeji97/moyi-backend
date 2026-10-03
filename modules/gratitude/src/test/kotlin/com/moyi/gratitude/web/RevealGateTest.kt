package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-060's acceptance clause, parameterised. A failure here is a P0 (doc 11).
 * C1 covers the statuses C1 can produce; C2 extends the matrix to REVEALED
 * and C3 to SOLO, and neither may narrow what is asserted here.
 *
 * `EntriesEndpointTest`'s own harness — a paired bond (`ada` and `bea`)
 * through the real `bond` endpoints [GratitudeTestApplication] scans for,
 * against a real Postgres, truncated between tests.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class RevealGateTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
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
    fun `a withdrawn entry is a tombstone for everyone, its author included`() {
        // Erasure beats both of BR-1's grants: authorship and a reveal. The
        // row's text is deliberately LEFT IN PLACE in every state below, so
        // the only thing between the words and the wire is the gate — a gate
        // without the tombstone clause hands them to the author (always) and
        // to the partner (once revealed), and `shouldNotContain` fails.
        submit(ada, bondId, """{"text":"words I took back"}""").status shouldBe 201
        submit(bea, bondId, """{"text":"bea's own"}""").status shouldBe 201

        val erasedStates =
            listOf(
                "status = 'DELETED', deleted_at = now(), revealed_at = NULL",
                "status = 'SUBMITTED', deleted_at = now(), revealed_at = NULL",
                "status = 'DELETED', deleted_at = NULL, revealed_at = NULL",
                "status = 'REVEALED', deleted_at = now(), revealed_at = now()",
            )
        for (state in erasedStates) {
            jdbc.update("UPDATE entries SET $state WHERE author_member_id = ?::uuid", memberIdOf(ada))

            val asAuthor = getToday(ada, bondId).contentAsString
            val asPartner = getToday(bea, bondId).contentAsString

            withClue(state) {
                myEntryJson(asAuthor) shouldContain "\"text\":null"
                myEntryJson(asAuthor) shouldContain "\"status\":\"DELETED\""
                asAuthor shouldNotContain "words I took back"
                partnerEntryJson(asPartner) shouldContain "\"text\":null"
                partnerEntryJson(asPartner) shouldContain "\"status\":\"DELETED\""
                asPartner shouldNotContain "words I took back"
            }
        }
    }

    @Test
    fun `a non-member reads nothing, regardless of reveal state`() {
        // Membership is checked FIRST: a revealed entry is not public.
        submit(bea, bondId, """{"text":"revealed, not published"}""").status shouldBe 201
        revealByHand(bea)
        setDayByHand("REVEALED")
        val stranger = users.verified("Eve")

        val today = getToday(stranger, bondId)

        today.status shouldBe 404
        today.contentAsString shouldNotContain "revealed, not published"
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

    // ---- helpers --------------------------------------------------------

    /** C2's reveal, by hand: the entry's own timestamp and status, nothing else. */
    private fun revealByHand(author: UUID) {
        jdbc.update("UPDATE entries SET revealed_at = now(), status = 'REVEALED' WHERE author_member_id = ?::uuid", memberIdOf(author)) shouldBe 1
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

    /** `partnerEntry`'s own JSON object, scoped out of the whole response body — fix round 1, M2. */
    private fun partnerEntryJson(body: String): String = PARTNER_ENTRY.find(body)!!.groupValues[1]

    /** `myEntry`'s own JSON object, scoped the same way. */
    private fun myEntryJson(body: String): String = MY_ENTRY.find(body)!!.groupValues[1]

    private companion object {
        val PARTNER_ENTRY = Regex(""""partnerEntry":(\{[^}]*})""")
        val MY_ENTRY = Regex(""""myEntry":(\{[^}]*})""")

        /** Doc 04 §3's eight, as V12's CHECK spells them. */
        val DAY_STATUSES = listOf("OPEN", "PARTIAL", "PENDING_REVEAL", "REVEALED", "SOLO", "EMPTY", "SUSPENDED", "FROZEN")
    }
}
