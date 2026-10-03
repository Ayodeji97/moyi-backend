package com.moyi.bond.service

import com.moyi.bond.domain.AnchorInterval
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * BR-6 and ADR-0030, end to end: a confirmed anchor change is recorded on the
 * bond at once (B5's own behaviour, unaffected) but does not decide dates
 * until the deferred handoff `AnchorTimeline` computes (Phase 3 slice C1
 * rework, task 2).
 *
 * Runs through the real HTTP endpoints — `BondTimezoneEndpointTest`'s own
 * precedent — because what is on trial is the whole chain: `ChangeTimezone`,
 * `AnchorIntervalStore`, and V13's partial unique index, together. The clock
 * is overridden with a [MutableClock] (the `EntriesEndpointTest` precedent in
 * `gratitude`) because every assertion here is about which *instant* a day
 * boundary falls on, which the real wall clock cannot give a test control
 * over.
 *
 * `BondTestApplication` wires a `@Primary` high-resolution
 * [Clock][java.time.Clock] that **wraps** `common:core`'s own `clock` bean
 * (`@Qualifier("clock")`), rather than being a second, competing `@Primary`
 * candidate itself. [clock] uses [TestBean] to replace that inner `clock`
 * bean by name — deterministically, regardless of which configuration class
 * originally registered it — so every consumer still resolves the one
 * `@Primary` `highResolutionClock`, and that bean's own wrapping (the
 * sub-microsecond offset, truncated away by every service's own
 * `truncatedTo(MICROS)`) is still exercised exactly as it is for every other
 * bond test. `allow-bean-definition-overriding` below is what lets `TestBean`
 * register its replacement under that existing bean name.
 */
@SpringBootTest(
    classes = [BondTestApplication::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@AutoConfigureMockMvc
internal class DeferredTimezoneChangeTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    @TestBean(name = "clock")
    private lateinit var clock: MutableClock

    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_anchor_intervals, bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `confirming records the request immediately and defers the effect to the next day`() {
        val bond = bondForTwo(anchor = "Africa/Lagos", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        val confirmedAt = Instant.parse("2026-09-15T11:00:00Z") // midday, Lagos

        proposeAndConfirm(bond, to = "Pacific/Kiritimati", at = confirmedAt)

        // B5's behaviour is unchanged: the bond says what was agreed.
        bondRow(bond).anchorTimezone shouldBe "Pacific/Kiritimati"
        // BR-6: it does not decide dates yet.
        val timeline = intervals(bond)
        timeline.zoneAt(confirmedAt) shouldBe ZoneId.of("Africa/Lagos")
        timeline.zoneAt(Instant.parse("2026-09-15T23:00:00Z")) shouldBe ZoneId.of("Pacific/Kiritimati")
    }

    @Test
    fun `the deferred handoff closes the open interval rather than adding a second one`() {
        val bond = bondForTwo(anchor = "Africa/Lagos", createdAt = Instant.parse("2026-09-01T00:00:00Z"))

        proposeAndConfirm(bond, to = "Pacific/Kiritimati", at = Instant.parse("2026-09-15T11:00:00Z"))

        val rows = rawIntervals(bond)
        rows.size shouldBe 2
        rows.count { it.effectiveTo == null } shouldBe 1
        rows[0].effectiveTo shouldBe rows[1].effectiveFrom
    }

    @Test
    fun `a bond seeds one open interval when it is created`() {
        val bond = bondForTwo(anchor = "Europe/London", createdAt = Instant.parse("2026-09-01T00:00:00Z"))

        val rows = rawIntervals(bond)
        rows.size shouldBe 1
        rows.single().zone shouldBe "Europe/London"
        rows.single().effectiveFrom shouldBe Instant.parse("2026-09-01T00:00:00Z")
        rows.single().effectiveTo shouldBe null
    }

    @Test
    fun `a westward change never schedules a handoff onto a label the bond has used`() {
        val bond = bondForTwo(anchor = "Pacific/Kiritimati", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        liveInto(bond, LocalDate.of(2026, 9, 15))
        liveInto(bond, LocalDate.of(2026, 9, 16))

        // The clock is already at 2026-09-15T11:00Z after the two `liveInto`
        // calls above (01:00 on the 16th, Kiritimati time) — passed here as
        // the literal instant it actually is, not a stale one `proposeAndConfirm`
        // would silently ignore (fix round 1, minor (a)).
        proposeAndConfirm(bond, to = "Pacific/Honolulu", at = Instant.parse("2026-09-15T11:00:00Z"))

        // Confirms the handoff fired at all (rules out the mechanism simply
        // being absent) before asserting anything about where.
        val rows = rawIntervals(bond)
        rows.size shouldBe 2
        rows.single { it.effectiveTo == null }.zone shouldBe "Pacific/Honolulu"

        val timeline = intervals(bond)
        // Kiritimati alone (UTC+14, no handoff at all) would already report
        // the 17th for a naive, zone-only date at the instant below, so a
        // bare `dateAt` assertion there cannot tell a correctly pushed-back
        // handoff from one that was never applied — or from one pushed one
        // day too far, onto the 17th itself, which Honolulu's own interval
        // issues as *its* first label (fix round 1, Important #2). The 15th
        // and 16th are already used, so R3 extends the 16th into this merged,
        // nearly-48-hour day instead, up to the handoff — which is what these
        // two assertions pin, before confirming the 17th belongs to Honolulu.
        val mergedDay = timeline.dayBoundsAt(Instant.parse("2026-09-16T10:00:00Z"))
        mergedDay.date shouldBe LocalDate.of(2026, 9, 16)
        mergedDay.endsAt shouldBe Instant.parse("2026-09-17T10:00:00Z")
        timeline.dateAt(Instant.parse("2026-09-17T10:00:00Z")) shouldBe LocalDate.of(2026, 9, 17)
        timeline.zoneAt(Instant.parse("2026-09-17T10:00:00Z")) shouldBe ZoneId.of("Pacific/Honolulu")
    }

    @Test
    fun `the PENDING_MEMBER early-apply path defers to the timeline too`() {
        clock.set(Instant.parse("2026-09-01T00:00:00Z"))
        val ada = users.verified("Ada")
        val created = createBond(ada, "Africa/Lagos")
        val bondId = bondIdOf(created)
        val code = codeOf(created)

        // Within the invite's own 7-day TTL (`Invite.TTL`) — unlike the
        // propose+confirm scenarios above, the partner still has to join on
        // this path, through a code that would otherwise have lapsed by the
        // time this test could assert anything about it.
        val appliedAt = Instant.parse("2026-09-03T11:00:00Z") // midday, Lagos
        clock.set(appliedAt)
        propose(ada, bondId, "Pacific/Kiritimati").status shouldBe 200

        // The partner joins afterwards, at the same instant — the seed
        // interval is not rewritten for them either.
        val bea = users.verified("Bea")
        accept(bea, code).status shouldBe 200

        val bond = TestBond(bondId, ada, bea, ZoneId.of("Africa/Lagos"))
        val rows = rawIntervals(bond)
        rows.size shouldBe 2

        val timeline = intervals(bond)
        // Lagos' own day ends 2026-09-03T23:00Z; nothing is used yet at this
        // young a bond, so the handoff lands exactly there with no push.
        timeline.zoneAt(appliedAt) shouldBe ZoneId.of("Africa/Lagos")
        timeline.zoneAt(Instant.parse("2026-09-03T23:00:00Z")) shouldBe ZoneId.of("Pacific/Kiritimati")
    }

    // ---- fixtures -------------------------------------------------------

    /** A row as the table actually holds it — `zone` is text, not a parsed [ZoneId], so a mistaken write is visible as a string. */
    private data class RawInterval(
        val zone: String,
        val effectiveFrom: Instant,
        val effectiveTo: Instant?,
    )

    private data class BondRow(
        val anchorTimezone: String,
    )

    private data class TestBond(
        val id: String,
        val ada: UUID,
        val bea: UUID,
        val zone: ZoneId,
    )

    /** Ada creates, Bea joins — the ordinary two-member bond every consent rule is about, at a controlled instant. */
    private fun bondForTwo(
        anchor: String,
        createdAt: Instant,
    ): TestBond {
        clock.set(createdAt)
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada, anchor)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        return TestBond(bondId, ada, bea, ZoneId.of(anchor))
    }

    /**
     * Proposes and confirms a zone change, moving the clock to [at] first —
     * never backward, so a prior [liveInto] call that already carried the
     * clock past [at] is left alone rather than rewound. `now` inside
     * `ChangeTimezone.confirm` is read from the same injected clock, so this
     * is what fixes the instant the deferred handoff is computed against.
     */
    private fun proposeAndConfirm(
        bond: TestBond,
        to: String,
        at: Instant,
    ) {
        if (at.isAfter(clock.instant())) clock.set(at)
        propose(bond.ada, bond.id, to).status shouldBe 200
        confirm(bond.bea, bond.id).status shouldBe 200
    }

    /**
     * Carries the clock forward into [date] (in the bond's own anchor zone)
     * if it is not there already — never backward — simulating that the bond
     * has lived through that calendar label. `usedLabelsUpTo` derives every
     * label a bond has issued purely from elapsed time (doc 25 §6, ADR-0026):
     * there is no `bond_days` row for `bond` to write or read, so moving the
     * clock is the only fixture this mechanism has; nothing is written here.
     */
    private fun liveInto(
        bond: TestBond,
        date: LocalDate,
    ) {
        val within = date.atStartOfDay(bond.zone).toInstant().plusSeconds(3600)
        if (within.isAfter(clock.instant())) clock.set(within)
    }

    private fun intervals(bond: TestBond): AnchorTimeline =
        AnchorTimeline(
            jdbc.query(
                "SELECT zone, first_label, effective_from, effective_to FROM bond_anchor_intervals " +
                    "WHERE bond_id = ? ORDER BY effective_from",
                { rs, _ ->
                    AnchorInterval(
                        zone = ZoneId.of(rs.getString("zone")),
                        effectiveFrom = rs.getTimestamp("effective_from").toInstant(),
                        effectiveTo = rs.getTimestamp("effective_to")?.toInstant(),
                        firstLabel = rs.getDate("first_label").toLocalDate(),
                    )
                },
                UUID.fromString(bond.id),
            ),
        )

    private fun rawIntervals(bond: TestBond): List<RawInterval> =
        jdbc.query(
            "SELECT zone, effective_from, effective_to FROM bond_anchor_intervals WHERE bond_id = ? ORDER BY effective_from",
            { rs, _ ->
                RawInterval(
                    zone = rs.getString("zone"),
                    effectiveFrom = rs.getTimestamp("effective_from").toInstant(),
                    effectiveTo = rs.getTimestamp("effective_to")?.toInstant(),
                )
            },
            UUID.fromString(bond.id),
        )

    private fun bondRow(bond: TestBond): BondRow =
        BondRow(
            jdbc.queryForObject(
                "SELECT anchor_timezone FROM bonds WHERE id = ?",
                String::class.java,
                UUID.fromString(bond.id),
            )!!,
        )

    // ---- HTTP helpers — `BondTimezoneEndpointTest`'s own shape -----------

    private fun createBond(
        userId: UUID,
        anchor: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$anchor"}"""
            }.andReturn()
            .response

    private fun propose(
        userId: UUID,
        bondId: String,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun confirm(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"proposalId":"${jdbc
                        .queryForList(
                            "SELECT id FROM bond_proposals WHERE bond_id = ? AND kind = 'TIMEZONE_CHANGE' ORDER BY proposed_at DESC",
                            UUID.fromString(bondId),
                        ).firstOrNull()
                        ?.get("id") ?: UUID.randomUUID()}"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{6})"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        /** [TestBean]'s default naming convention: a static method named after the field it overrides. */
        @JvmStatic
        fun clock(): MutableClock = MutableClock(start = Instant.parse("2020-01-01T00:00:00Z"))
    }
}
