package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The timezone matrix (spec §9: "spring forward, fall back, Kathmandu,
 * Chatham, members ≥12 h apart, a date-line crossing"), end to end: every
 * case drives `POST /bonds/{id}/entries` and `GET /bonds/{id}/today` and then
 * reads the **persisted** `bond_days.starts_at` / `ends_at` and label.
 *
 * Every expected instant is worked out in the comment beside its case, and
 * the zone facts it rests on (offsets, transition instants) are asserted
 * against `java.time` in the test itself rather than trusted from memory.
 *
 * Two obvious wrong implementations, and which cases each one fails
 * (mutation-checked against `AnchorTimeline.dayBoundsAt`):
 *
 * - **UTC midnight** — the day is the UTC calendar date. Fails every case.
 * - **fixed 24 h** — `date.atStartOfDay(zone)` to `+24h`. Fails the three
 *   DST cases (1, 2 and Chatham's transition day) and no other: Kathmandu,
 *   Chatham in standard time and the two members' day (5) are honest
 *   24-hour days, and the date-line spans (6a, 6b) come from clipping and
 *   merging at the handoff, not from a zone's natural day length. Those
 *   cases are told apart from a whole-hour offset or a writer's own zone
 *   instead — a write one second either side of the :15 boundary, two
 *   writers whose own calendars disagree.
 *
 * **The eastward date-line case asserts what C1 guarantees, not a `FROZEN`
 * row** (ruling P9): nothing in C1 writes a row for a skipped label, because
 * no instant belongs to it and `gratitude` only opens a day an entry lands
 * on. **C3's close job settles the skipped label FROZEN (spec §13,
 * BR-6/§8.5).** An `intendedAt` aimed at the skipped label is not refused
 * and does not fall back: it is an *accepted* claim, resolved through the
 * timeline onto the neighbouring label that instant really belongs to.
 *
 * **The westward cases also pin ruling P10**: a row opened before the change
 * has its `ends_at` extended, under the day lock, by the next write that
 * touches it — unless the day is settled, which is never extended.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(TimezoneMatrixTest.TimeConfiguration::class)
internal class TimezoneMatrixTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val access: BondAccess,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        // The pool first, and awaited: a submission still queued after a
        // failed assertion must not commit after the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    // ---- 1. DST spring forward: a 23-hour day ---------------------------

    /**
     * `America/New_York`, Sunday 8 March 2026. EST is UTC-5, EDT is UTC-4; the
     * clocks go 02:00 EST -> 03:00 EDT at 2026-03-08T07:00Z.
     *
     * - 8th starts 00:00 EST = 2026-03-08T05:00Z
     * - 8th ends   00:00 EDT on the 9th = 2026-03-09T04:00Z  -> 23 h
     * - 9th is     [03-09T04:00Z, 03-10T04:00Z), 24 h, all EDT
     *
     * Fixed-24h ends the 8th at 03-09T05:00Z, and so files a write at
     * 03-09T04:30Z (00:30 EDT on the 9th) on the 8th. UTC-midnight starts the
     * 8th at 03-08T00:00Z.
     */
    @Test
    fun `a spring-forward day is 23 hours long, and the next day starts an hour early in UTC`() {
        val newYork = ZoneId.of("America/New_York")
        val transition = newYork.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
        transition.instant shouldBe Instant.parse("2026-03-08T07:00:00Z")
        transition.isGap shouldBe true
        transition.offsetBefore shouldBe ZoneOffset.ofHours(-5)
        transition.offsetAfter shouldBe ZoneOffset.ofHours(-4)

        pairedBond("America/New_York", createdAt = "2026-03-04T12:00:00Z")

        clock.set(Instant.parse("2026-03-08T12:00:00Z")) // 08:00 EDT on the 8th
        submit(ada).contentAsString shouldContain "\"date\":\"2026-03-08\""
        clock.set(Instant.parse("2026-03-09T04:30:00Z")) // 00:30 EDT on the 9th
        submit(bea).contentAsString shouldContain "\"date\":\"2026-03-09\""

        dayRows().shouldContainExactly(
            DayRow("2026-03-08", "America/New_York", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", entries = 1),
            DayRow("2026-03-09", "America/New_York", "2026-03-09T04:00:00Z", "2026-03-10T04:00:00Z", entries = 1),
        )
        hoursOf("2026-03-08") shouldBe 23
    }

    // ---- 2. DST fall back: a 25-hour day --------------------------------

    /**
     * `Europe/London`, Sunday 25 October 2026. BST is UTC+1, GMT is UTC+0; the
     * clocks go 02:00 BST -> 01:00 GMT at 2026-10-25T01:00Z.
     *
     * - 25th starts 00:00 BST = 2026-10-24T23:00Z
     * - 25th ends   00:00 GMT on the 26th = 2026-10-26T00:00Z  -> 25 h
     *
     * Both writes are on the 25th: 10-25T00:30Z is 01:30 BST (the first
     * 01:30), and 10-25T23:30Z is 23:30 GMT — inside the twenty-fifth hour.
     * Fixed-24h ends the day at 10-25T23:00Z and files the second write on the
     * 26th; UTC-midnight starts it at 10-25T00:00Z.
     */
    @Test
    fun `a fall-back day is 25 hours long, and a write in its last hour is still that day`() {
        val london = ZoneId.of("Europe/London")
        val transition = london.rules.nextTransition(Instant.parse("2026-06-01T00:00:00Z"))
        transition.instant shouldBe Instant.parse("2026-10-25T01:00:00Z")
        transition.isOverlap shouldBe true
        transition.offsetBefore shouldBe ZoneOffset.ofHours(1)
        transition.offsetAfter shouldBe ZoneOffset.UTC

        pairedBond("Europe/London", createdAt = "2026-10-21T12:00:00Z")

        clock.set(Instant.parse("2026-10-25T00:30:00Z")) // 01:30 BST
        submit(ada).contentAsString shouldContain "\"date\":\"2026-10-25\""
        clock.set(Instant.parse("2026-10-25T23:30:00Z")) // 23:30 GMT, hour 25
        submit(bea).contentAsString shouldContain "\"date\":\"2026-10-25\""

        dayRows().shouldContainExactly(
            DayRow("2026-10-25", "Europe/London", "2026-10-24T23:00:00Z", "2026-10-26T00:00:00Z", entries = 2),
        )
        hoursOf("2026-10-25") shouldBe 25
    }

    // ---- 3. Asia/Kathmandu, UTC+5:45 ------------------------------------

    /**
     * `Asia/Kathmandu` is UTC+5:45 all year (no DST; no transition after 2026).
     *
     * - 10 June starts 00:00 NPT = 2026-06-09T18:15Z, ends 2026-06-10T18:15Z
     * - 11 June is     [06-10T18:15Z, 06-11T18:15Z)
     *
     * The boundary is on a quarter hour, so 18:14:59Z is still the 10th and
     * 18:15:00Z is the 11th. UTC-midnight puts both on the 10th; an offset
     * rounded to +5 or +6 moves the boundary to 19:00Z or 18:00Z.
     */
    @Test
    fun `Kathmandu's day turns over on the quarter hour`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        kathmandu.rules.getOffset(Instant.parse("2026-06-10T00:00:00Z")) shouldBe ZoneOffset.ofHoursMinutes(5, 45)
        kathmandu.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z")) shouldBe null

        pairedBond("Asia/Kathmandu", createdAt = "2026-06-07T00:00:00Z")

        clock.set(Instant.parse("2026-06-10T18:14:59Z")) // 23:59:59 NPT on the 10th
        submit(ada).contentAsString shouldContain "\"date\":\"2026-06-10\""
        today(ada).contentAsString shouldContain "\"date\":\"2026-06-10\""
        clock.set(Instant.parse("2026-06-10T18:15:00Z")) // 00:00:00 NPT on the 11th
        today(bea).contentAsString shouldContain "\"date\":\"2026-06-11\""
        submit(bea).contentAsString shouldContain "\"date\":\"2026-06-11\""

        dayRows().shouldContainExactly(
            DayRow("2026-06-10", "Asia/Kathmandu", "2026-06-09T18:15:00Z", "2026-06-10T18:15:00Z", entries = 1),
            DayRow("2026-06-11", "Asia/Kathmandu", "2026-06-10T18:15:00Z", "2026-06-11T18:15:00Z", entries = 1),
        )
    }

    // ---- 4. Pacific/Chatham, UTC+12:45 / +13:45 -------------------------

    /**
     * `Pacific/Chatham` in July is on standard time, UTC+12:45 (DST, +13:45,
     * runs from late September to early April — it is winter there in July).
     *
     * - 15 July starts 00:00 = 2026-07-14T11:15Z, ends 2026-07-15T11:15Z
     * - 16 July is     [07-15T11:15Z, 07-16T11:15Z)
     *
     * 07-15T00:00Z is 12:45 on the 15th; 07-15T11:15:00Z is 00:00 on the 16th.
     * UTC-midnight puts both writes on the 15th.
     */
    @Test
    fun `Chatham in standard time is twelve and three quarter hours ahead`() {
        val chatham = ZoneId.of("Pacific/Chatham")
        chatham.rules.getOffset(Instant.parse("2026-07-15T00:00:00Z")) shouldBe ZoneOffset.ofHoursMinutes(12, 45)
        chatham.rules.isDaylightSavings(Instant.parse("2026-07-15T00:00:00Z")) shouldBe false

        pairedBond("Pacific/Chatham", createdAt = "2026-07-12T00:00:00Z")

        clock.set(Instant.parse("2026-07-15T00:00:00Z")) // 12:45 on the 15th
        submit(ada).contentAsString shouldContain "\"date\":\"2026-07-15\""
        clock.set(Instant.parse("2026-07-15T11:15:00Z")) // 00:00 on the 16th
        submit(bea).contentAsString shouldContain "\"date\":\"2026-07-16\""

        dayRows().shouldContainExactly(
            DayRow("2026-07-15", "Pacific/Chatham", "2026-07-14T11:15:00Z", "2026-07-15T11:15:00Z", entries = 1),
            DayRow("2026-07-16", "Pacific/Chatham", "2026-07-15T11:15:00Z", "2026-07-16T11:15:00Z", entries = 1),
        )
    }

    /**
     * Chatham's own spring forward, Sunday 27 September 2026: 02:45 (+12:45)
     * -> 03:45 (+13:45) at 2026-09-26T14:00Z. A :45 offset *and* a 23-hour day.
     *
     * - 27th starts 00:00 +12:45 = 2026-09-26T11:15Z
     * - 27th ends   00:00 +13:45 on the 28th = 2026-09-27T10:15Z  -> 23 h
     * - 28th is     [09-27T10:15Z, 09-28T10:15Z), 24 h, all +13:45
     *
     * Fixed-24h ends the 27th at 09-27T11:15Z and so files a write at
     * 09-27T10:30Z (00:15 on the 28th) on the 27th.
     */
    @Test
    fun `Chatham's spring-forward day is 23 hours, on the quarter hour at both ends`() {
        val chatham = ZoneId.of("Pacific/Chatham")
        val transition = chatham.rules.nextTransition(Instant.parse("2026-06-01T00:00:00Z"))
        transition.instant shouldBe Instant.parse("2026-09-26T14:00:00Z")
        transition.isGap shouldBe true
        transition.offsetBefore shouldBe ZoneOffset.ofHoursMinutes(12, 45)
        transition.offsetAfter shouldBe ZoneOffset.ofHoursMinutes(13, 45)

        pairedBond("Pacific/Chatham", createdAt = "2026-09-24T00:00:00Z")

        clock.set(Instant.parse("2026-09-26T20:00:00Z")) // 09:45 (+13:45) on the 27th
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-27\""
        clock.set(Instant.parse("2026-09-27T10:30:00Z")) // 00:15 (+13:45) on the 28th
        submit(bea).contentAsString shouldContain "\"date\":\"2026-09-28\""

        dayRows().shouldContainExactly(
            DayRow("2026-09-27", "Pacific/Chatham", "2026-09-26T11:15:00Z", "2026-09-27T10:15:00Z", entries = 1),
            DayRow("2026-09-28", "Pacific/Chatham", "2026-09-27T10:15:00Z", "2026-09-28T10:15:00Z", entries = 1),
        )
        hoursOf("2026-09-27") shouldBe 23
    }

    // ---- 5. Two members >= 12 h apart, one bond-day ---------------------

    /**
     * The bond is anchored in `Africa/Lagos` (UTC+1, no DST). Ada is in
     * Honolulu (UTC-10), Bea in Auckland (UTC+12 in June, NZST) — 22 hours
     * apart. Neither device zone is sent anywhere; only the anchor decides.
     *
     * Lagos's 10 June is [2026-06-09T23:00Z, 2026-06-10T23:00Z).
     *
     * | write            | Lagos (anchor)  | writer's own wall clock  | UTC date |
     * |------------------|-----------------|--------------------------|----------|
     * | Ada 06-09T23:30Z | 10 June, 00:30  | Honolulu  9 June, 13:30  | 9 June   |
     * | Bea 06-10T13:00Z | 10 June, 14:00  | Auckland 11 June, 01:00  | 10 June  |
     *
     * One `bond_days` row, the 10th, though Ada's calendar says the 9th and
     * Bea's the 11th. A day taken from the writer's zone gives two rows (9th,
     * 11th); UTC-midnight gives two rows (9th, 10th).
     */
    @Test
    fun `two members 22 hours apart write into one bond-day, whatever their own calendars say`() {
        val adaWrites = Instant.parse("2026-06-09T23:30:00Z")
        val beaWrites = Instant.parse("2026-06-10T13:00:00Z")
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val auckland = ZoneId.of("Pacific/Auckland")
        honolulu.rules.getOffset(adaWrites) shouldBe ZoneOffset.ofHours(-10)
        auckland.rules.getOffset(beaWrites) shouldBe ZoneOffset.ofHours(12)
        adaWrites.atZone(honolulu).toLocalDate() shouldBe LocalDate.parse("2026-06-09")
        beaWrites.atZone(auckland).toLocalDate() shouldBe LocalDate.parse("2026-06-11")
        adaWrites.atZone(ZoneOffset.UTC).toLocalDate() shouldBe LocalDate.parse("2026-06-09")

        pairedBond("Africa/Lagos", createdAt = "2026-06-07T00:00:00Z")

        clock.set(adaWrites)
        submit(ada).contentAsString shouldContain "\"date\":\"2026-06-10\""
        clock.set(beaWrites)
        today(bea).contentAsString shouldContain "\"date\":\"2026-06-10\""
        submit(bea).contentAsString shouldContain "\"date\":\"2026-06-10\""

        dayRows().shouldContainExactly(
            DayRow("2026-06-10", "Africa/Lagos", "2026-06-09T23:00:00Z", "2026-06-10T23:00:00Z", entries = 2),
        )
        // The entries themselves, not only the row's own count of them.
        jdbc.queryForObject(
            "SELECT count(DISTINCT bond_day_id) || ',' || count(*) FROM entries WHERE bond_id = ?::uuid",
            String::class.java,
            bondId,
        ) shouldBe "1,2"
    }

    // ---- 6a. The date line, eastward: a label is skipped ----------------

    /**
     * `Pacific/Pago_Pago` (UTC-11) -> `Pacific/Kiritimati` (UTC+14), neither
     * with DST: 25 hours east, across the date line.
     *
     * - Agreed at 2026-09-15T20:00Z = 09:00 on the 15th in Pago Pago.
     * - Pago Pago's 15th is [09-15T11:00Z, 09-16T11:00Z); BR-6 defers the
     *   change to its end, so the handoff is **09-16T11:00Z**.
     * - In Kiritimati that instant is 01:00 on the **17th**. The 16th never
     *   happens for this bond: Pago Pago's 16th would be [09-16T11Z, 09-17T11Z)
     *   and Kiritimati's was [09-15T10Z, 09-16T10Z), and at no instant is the
     *   zone that would call it the 16th the one in charge.
     * - Kiritimati's 17th, clipped to the handoff: [09-16T11:00Z, 09-17T10:00Z),
     *   23 hours.
     *
     * What C1 guarantees (ruling P9), each asserted below: the days either
     * side are the 15th and the 17th; **no row exists for the 16th**; an
     * `intendedAt` aimed at either zone's idea of the 16th is **accepted**
     * (stored as claimed — not a fallback, not a refusal) and resolved
     * through the timeline onto the neighbouring label that instant belongs
     * to, instead of opening the 16th; and the two persisted spans meet at
     * the handoff — the 15th's `ends_at` is the 17th's `starts_at`.
     *
     * The back-fill onto the 15th is accepted only because C1 has no close
     * job: the 15th is still unsettled at 09-16T20:00Z. Once C3 settles it at
     * the handoff, that same claim falls back to the submission-time day (the
     * 17th) under BR-3a, and this test's first `intendedAt` probe changes.
     *
     * C3's close job settles the skipped label FROZEN (spec §13, BR-6/§8.5).
     */
    @Test
    fun `an eastward crossing skips a label - no row for it, no way to write onto it, no gap in UTC`() {
        ZoneId.of("Pacific/Pago_Pago").rules.getOffset(HANDOFF_EAST) shouldBe ZoneOffset.ofHours(-11)
        ZoneId.of("Pacific/Kiritimati").rules.getOffset(HANDOFF_EAST) shouldBe ZoneOffset.ofHours(14)

        pairedBond("Pacific/Pago_Pago", createdAt = "2026-09-10T00:00:00Z")

        clock.set(Instant.parse("2026-09-15T19:00:00Z")) // 08:00 on the 15th, Pago Pago
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-15\""
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone(proposer = ada, confirmer = bea, zone = "Pacific/Kiritimati")

        // `today` either side of the handoff: the 15th, then the 17th.
        clock.set(HANDOFF_EAST.minusSeconds(1))
        today(ada).contentAsString shouldContain "\"date\":\"2026-09-15\""
        clock.set(HANDOFF_EAST)
        today(ada).contentAsString shouldContain "\"date\":\"2026-09-17\""

        clock.set(Instant.parse("2026-09-16T20:00:00Z")) // 10:00 on the 17th, Kiritimati
        // 09-16T09:00Z is 23:00 on the 16th by Kiritimati's clock — but Pago
        // Pago decided dates then, and there it was 22:00 on the 15th.
        val backFilled = submit(bea, intendedAt = "2026-09-16T09:00:00Z")
        backFilled.contentAsString shouldContain "\"date\":\"2026-09-15\""
        intendedAtOf(backFilled) shouldBe "2026-09-16T09:00:00Z"
        // 09-16T12:00Z is 01:00 on the 16th by Pago Pago's clock — but
        // Kiritimati decided dates by then, and there it was 02:00 on the 17th.
        // The 17th is also where a fallback would land, so the date cannot
        // tell accepted from fallen-back; the stored `intended_at` can — a
        // fallback stores the submission instant, 20:00Z.
        val claimed = submit(ada, intendedAt = "2026-09-16T12:00:00Z")
        claimed.contentAsString shouldContain "\"date\":\"2026-09-17\""
        intendedAtOf(claimed) shouldBe "2026-09-16T12:00:00Z"
        submit(bea).contentAsString shouldContain "\"date\":\"2026-09-17\""

        val rows = dayRows()
        rows.shouldContainExactly(
            DayRow("2026-09-15", "Pacific/Pago_Pago", "2026-09-15T11:00:00Z", "2026-09-16T11:00:00Z", entries = 2),
            DayRow("2026-09-17", "Pacific/Kiritimati", "2026-09-16T11:00:00Z", "2026-09-17T10:00:00Z", entries = 2),
        )
        rows.none { it.date == "2026-09-16" } shouldBe true
        rows[0].endsAt shouldBe rows[1].startsAt
        hoursOf("2026-09-17") shouldBe 23

        // The port agrees, as far as it can be asked: it answers for instants,
        // and no instant is on the skipped label, so the 16th's own degenerate
        // window is not something `BondAccess` can be asked for.
        val timeline = access.membershipOf(ada, UUID.fromString(bondId)).anchorTimeline
        timeline.dayBoundsAt(HANDOFF_EAST.minusSeconds(1)).date shouldBe LocalDate.parse("2026-09-15")
        timeline.dayBoundsAt(HANDOFF_EAST.minusSeconds(1)).endsAt shouldBe HANDOFF_EAST
        timeline.dayBoundsAt(HANDOFF_EAST).date shouldBe LocalDate.parse("2026-09-17")
        timeline.dayBoundsAt(HANDOFF_EAST).startsAt shouldBe HANDOFF_EAST
    }

    // ---- 6b. The date line, westward: a label is not reused -------------

    /**
     * `Pacific/Kiritimati` (UTC+14) -> `Pacific/Pago_Pago` (UTC-11): 25 hours
     * west, across the date line.
     *
     * - Agreed at 2026-09-15T20:00Z = 10:00 on the **16th** in Kiritimati,
     *   whose 16th is naturally [09-15T10:00Z, 09-16T10:00Z).
     * - A handoff at 09-16T10:00Z would start Pago Pago at 23:00 on the 15th —
     *   a label already used. Pushed to Pago Pago's next midnight, 09-16T11:00Z,
     *   it would open the 16th — the label the bond is on right now. Pushed
     *   again: Pago Pago's 17th begins at **09-17T11:00Z**. That is the handoff.
     * - So the 16th is one merged day (R3): it keeps its Kiritimati start and
     *   runs to the handoff — [09-15T10:00Z, 09-17T11:00Z), **49 hours**.
     * - Pago Pago's 17th follows: [09-17T11:00Z, 09-18T11:00Z).
     *
     * Nobody has written on the 16th when the change is agreed, so the row is
     * opened afterwards and carries the merged span. Three writes inside it,
     * each of which a naive label would put elsewhere:
     *
     * | write        | Kiritimati's own date | Pago Pago's own date | filed on |
     * |--------------|-----------------------|----------------------|----------|
     * | 09-16T20:00Z | 17th, 10:00           | 16th, 09:00          | the 16th |
     * | 09-17T10:30Z | 18th, 00:30           | 16th, 23:30          | the 16th |
     * | 09-17T12:00Z | 18th, 02:00           | 17th, 01:00          | the 17th |
     */
    @Test
    fun `a westward crossing reuses no label - the spanning day runs on to the successor's start`() {
        pairedBond("Pacific/Kiritimati", createdAt = "2026-09-10T00:00:00Z")
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone(proposer = ada, confirmer = bea, zone = "Pacific/Pago_Pago")

        clock.set(Instant.parse("2026-09-16T20:00:00Z"))
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-16\""
        clock.set(Instant.parse("2026-09-17T10:30:00Z"))
        today(bea).contentAsString shouldContain "\"date\":\"2026-09-16\""
        submit(bea).contentAsString shouldContain "\"date\":\"2026-09-16\""
        // The pushed handoff, to the second: still the 16th, then the 17th.
        clock.set(Instant.parse(HANDOFF_WEST).minusSeconds(1))
        today(ada).contentAsString shouldContain "\"date\":\"2026-09-16\""
        clock.set(Instant.parse(HANDOFF_WEST))
        today(ada).contentAsString shouldContain "\"date\":\"2026-09-17\""
        clock.set(Instant.parse("2026-09-17T12:00:00Z"))
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-17\""

        val rows = dayRows()
        rows.shouldContainExactly(
            DayRow("2026-09-16", "Pacific/Kiritimati", "2026-09-15T10:00:00Z", HANDOFF_WEST, entries = 2),
            DayRow("2026-09-17", "Pacific/Pago_Pago", HANDOFF_WEST, "2026-09-18T11:00:00Z", entries = 1),
        )
        rows.map { it.date }.toSet().size shouldBe rows.size
        rows[0].endsAt shouldBe rows[1].startsAt
        hoursOf("2026-09-16") shouldBe 49
    }

    /**
     * The same crossing, but **somebody had already written on the 16th when
     * the change was agreed** — the ordinary order of events. The row was
     * opened at 09-15T19:00Z with Kiritimati's natural span, ending
     * 09-16T10:00Z. The change then makes the 16th the merged day, ending at
     * the successor's start, 09-17T11:00Z, and a write at 09-16T20:00Z is
     * filed on it. The persisted `ends_at` has to say so: an entry lives on
     * this row whose instant the span must contain, and the 17th's row starts
     * at 09-17T11:00Z, so anything shorter leaves a hole in the bond's
     * calendar that C3's close job (which reads these columns) would act on.
     *
     * **Ruling P10 is what makes this hold.** `SubmitEntry` extends the row
     * under the day's lock, before the entry is inserted: the write at
     * 09-16T20:00Z finds the row ending 09-16T10:00Z, the calendar says the
     * 16th now ends 09-17T11:00Z, and `BondDay.extendedTo` moves `ends_at`
     * there — later only; `starts_at` and the Kiritimati snapshot stay. With
     * the extension removed, the row keeps `ends_at = 2026-09-16T10:00:00Z`
     * while holding an entry written ten hours past it, 25 hours short of
     * the 17th's start.
     */
    @Test
    fun `a westward crossing extends the spanning day even when its row was already open`() {
        pairedBond("Pacific/Kiritimati", createdAt = "2026-09-10T00:00:00Z")
        clock.set(Instant.parse("2026-09-15T19:00:00Z")) // 09:00 on the 16th, Kiritimati
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-16\""
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone(proposer = ada, confirmer = bea, zone = "Pacific/Pago_Pago")

        clock.set(Instant.parse("2026-09-16T20:00:00Z")) // Kiritimati's 17th, Pago Pago's 16th
        submit(bea).contentAsString shouldContain "\"date\":\"2026-09-16\""
        clock.set(Instant.parse("2026-09-17T12:00:00Z"))
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-17\""

        val rows = dayRows()
        rows.shouldContainExactly(
            DayRow("2026-09-16", "Pacific/Kiritimati", "2026-09-15T10:00:00Z", HANDOFF_WEST, entries = 2),
            DayRow("2026-09-17", "Pacific/Pago_Pago", HANDOFF_WEST, "2026-09-18T11:00:00Z", entries = 1),
        )
        rows[0].endsAt shouldBe rows[1].startsAt
    }

    /**
     * **A settled day is never extended** (ruling P10, rule 2) — shown where
     * it could actually go wrong: a write that reaches the pre-change row
     * *under its lock* and finds it settled.
     *
     * The crossing is 6c's. The 16th's row was opened before the change, so
     * it ends 09-16T10:00Z while the calendar's 16th now runs to 09-17T11:00Z.
     * At 09-17T12:00Z (01:00 on Pago Pago's 17th) Bea sends a draft with
     * `intendedAt` 09-16T20:00Z — on the merged 16th, 16 hours back, inside
     * BR-3a's window. The 16th is unsettled when the submission looks, so it
     * resolves there and queues on the row's lock, which this test holds on
     * its own connection, standing in for C3's close. The close stamps the
     * day `SOLO` and commits. The submission then holds a settled day whose
     * window ends later than its row: it must leave the row alone and
     * redirect once to the submission-time day (spec §6.1.3).
     *
     * The redirect commits, so an extension wrongly applied to the settled
     * row would commit with it and show here as a changed `ends_at`.
     */
    @Test
    fun `a settled day is not extended - a draft that finds it closed under the lock moves on and leaves its span alone`() {
        pairedBond("Pacific/Kiritimati", createdAt = "2026-09-10T00:00:00Z")
        clock.set(Instant.parse("2026-09-15T19:00:00Z"))
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-16\""
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone(proposer = ada, confirmer = bea, zone = "Pacific/Pago_Pago")
        clock.set(Instant.parse("2026-09-17T12:00:00Z"))

        withDayLockHeld("2026-09-16") { holderPid, closeAndCommit ->
            val submission = pool.submit<MockHttpServletResponse> { submit(bea, intendedAt = "2026-09-16T20:00:00Z") }
            await().atMost(Duration.ofSeconds(10)).until { submission.isDone || blockedBehind(holderPid) > 0 }
            // Done already would mean it never queued on the 16th's row.
            submission.isDone shouldBe false

            closeAndCommit("SOLO")

            val response = submission.get(10, TimeUnit.SECONDS)
            response.contentAsString shouldContain "\"date\":\"2026-09-17\""
            // Redirected, so the rejected claim is not what is stored.
            intendedAtOf(response) shouldBe "2026-09-17T12:00:00Z"
        }

        dayRows().shouldContainExactly(
            // Untouched: still the span it was opened with, one entry, Ada's.
            DayRow("2026-09-16", "Pacific/Kiritimati", "2026-09-15T10:00:00Z", "2026-09-16T10:00:00Z", entries = 1),
            DayRow("2026-09-17", "Pacific/Pago_Pago", HANDOFF_WEST, "2026-09-18T11:00:00Z", entries = 1),
        )
        jdbc.queryForObject(
            "SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = '2026-09-16'",
            String::class.java,
            bondId,
        ) shouldBe "SOLO"
    }

    // ---- 6e. A fast phone at 23:57, then the crossing (ruling P11) --------

    /**
     * **No row is ever opened for a day that has not begun** (ruling P11) —
     * shown where it used to break: the five-minute skew tolerance, a
     * westward handoff, and P10's extension, each right on its own.
     *
     * Kiritimati's 16th is [09-15T10:00Z, 09-16T10:00Z). At 09-16T09:57Z it
     * is 23:57 on the 16th; Ada's phone runs four minutes fast and sends
     * `intendedAt` 09-16T10:01Z — 00:01 on the 17th. Taken at its word, that
     * claim opens the **17th**'s row three minutes before the 17th begins,
     * starting 09-16T10:00Z.
     *
     * At 09-16T09:58Z the westward change of 6b is confirmed. The bond is on
     * the 16th, so the 16th becomes the merged day and the 17th now starts at
     * Pago Pago's midnight, **09-17T11:00Z** — 25 hours after the row that
     * was opened for it says. From then on every `POST /entries` on the 17th
     * finds that row, asks `BondDay.extendedTo` to bring it up to the
     * timeline's window, and fails its `starts_at` check: a `500` for both
     * members, all day.
     *
     * Under P11 the claim is accepted (nothing is refused, `201`) but resolved
     * at the submission instant: the entry is on the 16th, where the server
     * says it was written, and the 17th's row is first opened on the 17th.
     */
    @Test
    fun `a claim four minutes ahead across midnight is filed today, and a westward change cannot strand tomorrow's row`() {
        pairedBond("Pacific/Kiritimati", createdAt = "2026-09-10T00:00:00Z")

        clock.set(Instant.parse("2026-09-16T09:57:00Z")) // 23:57 on the 16th, Kiritimati
        val early = submit(ada, intendedAt = "2026-09-16T10:01:00Z") // 00:01 on the 17th, by the phone
        early.contentAsString shouldContain "\"date\":\"2026-09-16\""
        // Resolved at the submission instant: that, not the claim, is what is stored.
        intendedAtOf(early) shouldBe "2026-09-16T09:57:00Z"
        dayRows().map { it.date } shouldContainExactly listOf("2026-09-16")

        clock.set(Instant.parse("2026-09-16T09:58:00Z"))
        changeZone(proposer = ada, confirmer = bea, zone = "Pacific/Pago_Pago")

        clock.set(Instant.parse("2026-09-16T20:00:00Z")) // still the merged 16th
        submit(bea).contentAsString shouldContain "\"date\":\"2026-09-16\""
        clock.set(Instant.parse("2026-09-17T12:00:00Z")) // 01:00 on Pago Pago's 17th
        submit(ada).contentAsString shouldContain "\"date\":\"2026-09-17\""

        val rows = dayRows()
        rows.shouldContainExactly(
            DayRow("2026-09-16", "Pacific/Kiritimati", "2026-09-15T10:00:00Z", HANDOFF_WEST, entries = 2),
            DayRow("2026-09-17", "Pacific/Pago_Pago", HANDOFF_WEST, "2026-09-18T11:00:00Z", entries = 1),
        )
        // Every stored row starts where the timeline says its label starts.
        val timeline = access.membershipOf(ada, UUID.fromString(bondId)).anchorTimeline
        rows.forEach { row ->
            val window = timeline.dayBoundsAt(Instant.parse(row.startsAt))
            window.date shouldBe LocalDate.parse(row.date)
            window.startsAt shouldBe Instant.parse(row.startsAt)
        }
    }

    // ---- the close, standing in for C3 ----------------------------------

    /**
     * Holds the day row's `FOR UPDATE` on its own connection and hands [block]
     * the holder's backend pid and a `closeAndCommit(status)` that stamps the
     * day closed under that lock and commits — `SubmitEntrySettledDayTest`'s
     * harness. Rolled back if [block] never closed it, which frees a
     * submission still queued behind a failed assertion.
     */
    private fun withDayLockHeld(
        date: String,
        block: (holderPid: Int, closeAndCommit: (String) -> Unit) -> Unit,
    ) {
        val day = "bond_id = '$bondId'::uuid AND date = '$date'"
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(lockDayRow(connection, day)) { status ->
                    closeDay(connection, day, status)
                    connection.commit()
                }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    /** Takes the row lock and answers the backend pid that now holds it. */
    private fun lockDayRow(
        connection: Connection,
        day: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid() FROM bond_days WHERE $day FOR UPDATE").use {
                it.next() shouldBe true
                it.getInt(1)
            }
        }

    private fun closeDay(
        connection: Connection,
        day: String,
        status: String,
    ) {
        connection.createStatement().use {
            it.executeUpdate("UPDATE bond_days SET status = '$status', closed_at = now() WHERE $day") shouldBe 1
        }
    }

    /** How many backends are genuinely queued behind [holderPid] — never inferred from a sleep. */
    private fun blockedBehind(holderPid: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
            Int::class.java,
            holderPid,
        )!!

    // ---- helpers --------------------------------------------------------

    private data class DayRow(
        val date: String,
        val zone: String,
        val startsAt: String,
        val endsAt: String,
        val entries: Int,
    )

    /** Every persisted Bond-day of the bond under test, oldest label first. */
    private fun dayRows(): List<DayRow> =
        jdbc.query(
            "SELECT date::text, anchor_timezone, starts_at, ends_at, entry_count FROM bond_days " +
                "WHERE bond_id = ?::uuid ORDER BY date",
            { rs, _ ->
                DayRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant().toString(),
                    rs.getTimestamp(4).toInstant().toString(),
                    rs.getInt(5),
                )
            },
            bondId,
        )

    /** The persisted length of one day, computed by Postgres from the two columns. */
    private fun hoursOf(date: String): Int =
        jdbc.queryForObject(
            "SELECT (EXTRACT(EPOCH FROM (ends_at - starts_at)) / 3600)::int FROM bond_days " +
                "WHERE bond_id = ?::uuid AND date = ?::date",
            Int::class.java,
            bondId,
            date,
        )!!

    /**
     * A two-member bond anchored in [zone]. Created a few days before the day
     * under test — a bond's first day is clipped to its creation, and these
     * cases want whole days — and accepted at once, inside the invite's TTL.
     */
    private fun pairedBond(
        zone: String,
        createdAt: String,
    ) {
        clock.set(Instant.parse(createdAt))
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$zone"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        bondId = Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
    }

    /** Two-party consent (ADR-0030): one proposes, the other confirms, at the clock's current instant. */
    private fun changeZone(
        proposer: UUID,
        confirmer: UUID,
        zone: String,
    ) {
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(proposer))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response.status shouldBe 200
        val proposalId =
            jdbc.queryForObject(
                "SELECT id FROM bond_proposals WHERE bond_id = ?::uuid AND kind = 'TIMEZONE_CHANGE' " +
                    "ORDER BY proposed_at DESC LIMIT 1",
                UUID::class.java,
                bondId,
            )
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(confirmer))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposalId"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    /** Submits at the clock's current instant; the `201` is asserted here so a case reads as its dates. */
    private fun submit(
        caller: UUID,
        intendedAt: String? = null,
    ): MockHttpServletResponse {
        val claim = intendedAt?.let { ""","intendedAt":"$it"""" }.orEmpty()
        val response =
            mockMvc
                .post("/api/v1/bonds/$bondId/entries") {
                    header(HttpHeaders.AUTHORIZATION, bearer(caller))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"thank you"$claim}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        return response
    }

    private fun today(caller: UUID): MockHttpServletResponse {
        val response =
            mockMvc
                .get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
                .andReturn()
                .response
        response.status shouldBe 200
        return response
    }

    /** The entry's persisted `intended_at` — the claim if it was accepted, the submission instant if it fell back. */
    private fun intendedAtOf(response: MockHttpServletResponse): String {
        val entryId = Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]
        return jdbc
            .queryForObject("SELECT intended_at FROM entries WHERE id = ?::uuid", Timestamp::class.java, entryId)!!
            .toInstant()
            .toString()
    }

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-01-01T00:00:00Z"))
    }

    private companion object {
        /** Pago Pago's midnight ending its 15th — see the eastward case. */
        val HANDOFF_EAST: Instant = Instant.parse("2026-09-16T11:00:00Z")

        /** Pago Pago's midnight opening its 17th — see the westward case. */
        const val HANDOFF_WEST = "2026-09-17T11:00:00Z"
    }
}
