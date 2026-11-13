package com.moyi.bond.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondAdminLookup
import com.moyi.bond.api.PushAudiences
import com.moyi.bond.api.ReminderRoster
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeAccountStanding
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * A3: a bond is paused while a member who has not left is suspended, derived on every read from
 * identity's standing and never stored. Each case here is one clause of `Bond.isPausedFor`, or
 * one of the two span questions the closer asks (`wasSuspendedDuring`, `wasSuspendedAt`).
 */
@SpringBootTest(classes = [BondTestApplication::class])
@Suppress("LongParameterList", "TooManyFunctions") // A test's collaborators, each named; one test per clause.
internal class PausedBondTest(
    @Autowired private val access: BondAccess,
    @Autowired private val create: CreateBond,
    @Autowired private val acceptInvite: AcceptInvite,
    @Autowired private val guard: BondAccessGuard,
    @Autowired private val endBond: EndBond,
    @Autowired private val lookup: BondAdminLookup,
    @Autowired private val roster: ReminderRoster,
    @Autowired private val audiences: PushAudiences,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired directory: UserDirectory,
    @Autowired standing: AccountStanding,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val spans = standing as FakeAccountStanding
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        spans.reset()
    }

    @Test
    fun `both members see the bond paused while one is suspended, and not after the lift, by either read`() {
        val (bond, ada, bea) = pairedBond()

        pausedFor(ada, bond) shouldBe false
        spans.suspend(bea, NOW)
        pausedFor(ada, bond) shouldBe true
        pausedFor(bea, bond) shouldBe true
        lockedPausedFor(ada, bond) shouldBe true
        lockedPausedFor(bea, bond) shouldBe true
        spans.lift(bea, NOW.plusSeconds(60))
        pausedFor(ada, bond) shouldBe false
        lockedPausedFor(bea, bond) shouldBe false
    }

    @Test
    fun `with both members suspended, lifting one leaves the bond paused`() {
        val (bond, ada, bea) = pairedBond()
        spans.suspend(ada, NOW)
        spans.suspend(bea, NOW)

        spans.lift(ada, NOW.plusSeconds(60))

        pausedFor(bea, bond) shouldBe true
        spans.lift(bea, NOW.plusSeconds(120))
        pausedFor(bea, bond) shouldBe false
    }

    @Test
    fun `a bond that has ended is not paused, whatever the spans, and nor is one whose suspended member has left`() {
        val (ended, ada, bea) = pairedBond()
        spans.suspend(ada, NOW)
        spans.suspend(bea, NOW)
        endBond.leave(guard.membershipOf(UserId(bea), BondId(ended)), withdrawEntries = false)

        pausedFor(ada, ended) shouldBe false
        pausedFor(bea, ended) shouldBe false

        // A pending bond: the creator alone is the active member; once they leave nobody is.
        val solo =
            create
                .create(draft(users.verified("Cy")))
                .bond.id.value
        val cy = jdbc.queryForObject("SELECT user_id FROM bond_members WHERE bond_id = ?", UUID::class.java, solo)!!
        spans.suspend(cy, NOW)
        pausedFor(cy, solo) shouldBe true
        endBond.leave(guard.membershipOf(UserId(cy), BondId(solo)), withdrawEntries = false)
        pausedFor(cy, solo) shouldBe false
    }

    @Test
    fun `a member in two bonds pauses both`() {
        val ada = users.verified("Ada")
        val first = pairedBondOf(ada, users.verified("Bea"))
        val second = pairedBondOf(ada, users.verified("Cy"))

        spans.suspend(ada, NOW)

        pausedFor(ada, first) shouldBe true
        pausedFor(ada, second) shouldBe true
    }

    @Test
    fun `wasSuspendedDuring is half open on both ends, and sees a left member's past span`() {
        val (bond, ada, bea) = pairedBond()
        val start = NOW
        val end = NOW.plus(Duration.ofHours(24))

        // A span ending exactly where the day starts, and one starting exactly where it ends: no overlap.
        spans.suspend(ada, start.minus(Duration.ofHours(5)))
        spans.lift(ada, start)
        spans.suspend(bea, end)
        view(bond).wasSuspendedDuring(start, end) shouldBe false

        // An open span that starts inside the day.
        spans.reset()
        spans.suspend(ada, start.plusSeconds(1))
        view(bond).wasSuspendedDuring(start, end) shouldBe true

        // One that ends a microsecond into it, and one that starts a microsecond before its end.
        spans.reset()
        spans.suspend(ada, start.minusSeconds(60))
        spans.lift(ada, start.plusNanos(1000))
        view(bond).wasSuspendedDuring(start, end) shouldBe true
        spans.reset()
        spans.suspend(bea, end.minusNanos(1000))
        view(bond).wasSuspendedDuring(start, end) shouldBe true

        // A member who has since left keeps their row, and their past span still counts.
        spans.reset()
        spans.suspend(bea, start.plusSeconds(10))
        spans.lift(bea, start.plusSeconds(20))
        endBond.leave(guard.membershipOf(UserId(bea), BondId(bond)), withdrawEntries = false)
        view(bond).wasSuspendedDuring(start, end) shouldBe true
        view(bond).wasSuspendedDuring(end, end.plusSeconds(1)) shouldBe false
    }

    @Test
    fun `wasSuspendedAt includes the span's from and excludes its to`() {
        val (bond, ada, _) = pairedBond()
        spans.suspend(ada, NOW)
        spans.lift(ada, NOW.plusSeconds(60))

        view(bond).wasSuspendedAt(NOW.minusNanos(1000)) shouldBe false
        view(bond).wasSuspendedAt(NOW) shouldBe true
        view(bond).wasSuspendedAt(NOW.plusSeconds(60).minusNanos(1000)) shouldBe true
        view(bond).wasSuspendedAt(NOW.plusSeconds(60)) shouldBe false
    }

    @Test
    fun `the reminder roster drops a paused bond's reminders and keeps the on-this-day push and an unpaused bond's`() {
        val (paused, _, bea) = pairedBond("Ada" to "Bea")
        val (_, cal, dee) = pairedBond("Cal" to "Dee")
        jdbc.update("""UPDATE bond_members SET notification_prefs = '{"onThisDay":true}'::jsonb""")
        // 20:00 in Lagos is 19:00Z, tomorrow's: the default reminder, and the on-this-day time below.
        val windowEnd =
            Instant
                .now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(19))
        val after = windowEnd.minusSeconds(300)
        roster.remindersDueBetween(after, windowEnd).map { it.userId } shouldContainExactlyInAnyOrder users.ids(paused, cal, dee)

        spans.suspend(bea, NOW)

        // Ada and Bea's bond is paused: neither is reminded. Cal and Dee still are.
        roster.remindersDueBetween(after, windowEnd).map { it.userId } shouldContainExactlyInAnyOrder listOf(cal, dee)
        // The on-this-day push is not the reminder: both of the paused bond's members still have it.
        audiences.onThisDayDueBetween(LocalTime.of(20, 0), after, windowEnd).map { it.userId } shouldContainExactlyInAnyOrder
            users.ids(paused, cal, dee)

        spans.lift(bea, NOW.plusSeconds(60))
        roster.remindersDueBetween(after, windowEnd).size shouldBe 4
    }

    @Test
    fun `the admin view says a bond is paused`() {
        val (bond, ada, bea) = pairedBond()

        lookup.bondsOf(ada).single().paused shouldBe false
        spans.suspend(bea, NOW)
        lookup.bondsOf(ada).single { it.bondId == bond }.paused shouldBe true
        lookup.bondsOf(bea).single { it.bondId == bond }.paused shouldBe true
        spans.lift(bea, NOW.plusSeconds(60))
        lookup.bondsOf(ada).single().paused shouldBe false
        lookup.bondsOf(UUID.randomUUID()).shouldBeEmpty()
    }

    // ---- helpers ------------------------------------------------------------

    private fun view(bond: UUID) = access.closingViewOf(bond)!!

    private fun pausedFor(
        user: UUID,
        bond: UUID,
    ): Boolean = access.membershipOf(user, bond).paused

    private fun lockedPausedFor(
        user: UUID,
        bond: UUID,
    ): Boolean = transactions.execute { access.lockMembershipOf(user, bond).paused }!!

    private fun FakeUserDirectory.ids(
        bond: UUID,
        vararg others: UUID,
    ): List<UUID> = jdbc.queryForList("SELECT user_id FROM bond_members WHERE bond_id = ?", UUID::class.java, bond).filterNotNull() + others

    private fun pairedBond(names: Pair<String, String> = "Ada" to "Bea"): Triple<UUID, UUID, UUID> {
        val creator = users.verified(names.first)
        val joiner = users.verified(names.second)
        return Triple(pairedBondOf(creator, joiner), creator, joiner)
    }

    private fun pairedBondOf(
        creator: UUID,
        joiner: UUID,
    ): UUID {
        val view = create.create(draft(creator))
        acceptInvite.accept(UserId(joiner), view.invite!!.invite.code)
        return view.bond.id.value
    }

    private fun draft(creator: UUID) =
        BondDraft(
            creator = UserId(creator),
            type = BondType.COUPLE,
            name = "Us",
            anchorTimezone = RegionZone.of("Africa/Lagos"),
            revealTimeLocal = null,
            reminderTimezone = null,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-13T12:00:00Z")
    }
}
