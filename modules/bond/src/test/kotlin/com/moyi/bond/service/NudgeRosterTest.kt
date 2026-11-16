package com.moyi.bond.service

import com.moyi.bond.api.DueNudge
import com.moyi.bond.api.ReminderRoster
import com.moyi.bond.domain.BondDraft
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Time
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * The roster's nudges (#200, spec §4, rulings C12 and C17), over the real tables: which index
 * falls in a sweep's window, the one bond-day a series lies in, and the bond-days a nudge 2 or 3
 * counts as owed a reveal.
 *
 * Every bond here began on [BEGAN] unless a case says otherwise, and every case's day is in
 * January 2027, when London is on UTC: so a London wall time reads as the instant it is.
 */
@SpringBootTest(classes = [BondTestApplication::class])
internal class NudgeRosterTest(
    @Autowired private val roster: ReminderRoster,
    @Autowired private val create: CreateBond,
    @Autowired private val acceptInvite: AcceptInvite,
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

    // ---- the day rule (C12) -------------------------------------------------

    @Test
    fun `an 08_00 London reminder is nudged at 11_00 and 14_00, one bond-day, ending at London's midnight`() {
        val ada = users.verified("Ada")
        val bond = bondOf(ada, users.verified("Bea"), LONDON)
        val member = remind(ada, bond, "08:00", LONDON)

        val swept = listOf("08:00", "11:00", "14:00").map { sweep(member, DAY.at(it)).single() }

        swept.map { it.index } shouldBe listOf(1, 2, 3)
        swept.map { it.reminder.remindAt } shouldBe listOf(DAY.at("08:00"), DAY.at("11:00"), DAY.at("14:00"))
        swept.map { it.reminder.bondDate }.toSet() shouldBe setOf(DAY)
        swept.map { it.reminder.bondDayEndsAt }.toSet() shouldBe setOf(DAY.plusDays(1).at("00:00"))
        swept.map { it.reminder.bondId }.toSet() shouldBe setOf(bond)
        // Nothing between them: each index is due once, in its own window.
        sweep(member, DAY.at("09:30")).shouldBeEmpty()
    }

    @Test
    fun `a 20_00 reminder gets nudge 2 only, and a 22_00 one no nudge, the next one being past midnight`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = bondOf(ada, bea, LONDON)
        val evening = remind(ada, bond, "20:00", LONDON)
        val late = remind(bea, bond, "22:00", LONDON)

        sweep(evening, DAY.at("20:00")).map { it.index } shouldBe listOf(1)
        sweep(evening, DAY.at("23:00")).map { it.index } shouldBe listOf(2)
        // 02:00 is nudge 3's instant, in the next bond-day, which began after nudge 1.
        sweep(evening, DAY.plusDays(1).at("02:00")).shouldBeEmpty()

        sweep(late, DAY.at("22:00")).map { it.index } shouldBe listOf(1)
        // 01:00 is nudge 2's, 04:00 nudge 3's: both in the next bond-day.
        sweep(late, DAY.plusDays(1).at("01:00")).shouldBeEmpty()
        sweep(late, DAY.plusDays(1).at("04:00")).shouldBeEmpty()
    }

    @Test
    fun `a bond made at 09_30 by a member whose reminder is 08_00 gets no nudges that first day`() {
        val ada = users.verified("Ada")
        val bond = bondOf(ada, users.verified("Bea"), LONDON, began = DAY.at("09:30"))
        val member = remind(ada, bond, "08:00", LONDON)

        sweep(member, DAY.at("08:00")).shouldBeEmpty()
        sweep(member, DAY.at("11:00")).shouldBeEmpty()
        sweep(member, DAY.at("14:00")).shouldBeEmpty()
        // The next day's series is whole.
        listOf("08:00", "11:00", "14:00").map { sweep(member, DAY.plusDays(1).at(it)).single().index } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a 20_00 New York reminder in a Tokyo bond is three nudges in one Tokyo day, the third after New York's midnight`() {
        // 20:00 New York is 01:00Z, 10:00 in Tokyo; the nudges are 13:00 and 16:00 there. Nudge 3,
        // 07:00Z, is 02:00 in New York the next calendar day: the bond's day is the one that counts.
        val ada = users.verified("Ada")
        val bond = bondOf(ada, users.verified("Bea"), "Asia/Tokyo")
        val member = remind(ada, bond, "20:00", "America/New_York")
        val tokyoDay = DAY.plusDays(1)

        val swept = listOf("01:00", "04:00", "07:00").map { sweep(member, tokyoDay.at(it)).single() }

        swept.map { it.index } shouldBe listOf(1, 2, 3)
        swept.map { it.reminder.bondDate }.toSet() shouldBe setOf(tokyoDay)
        // Tokyo's midnight at the end of that day is 15:00Z.
        swept.map { it.reminder.bondDayEndsAt }.toSet() shouldBe setOf(tokyoDay.at("15:00"))
    }

    @Test
    fun `a 23_30 Reykjavik reminder in a Lagos bond is three nudges in the Lagos day it opens`() {
        // ADR-0045's case. 23:30 in Reykjavik is 23:30Z, 00:30 the next day in Lagos (UTC+1): the
        // Lagos day began at 23:00Z, before nudge 1, so all three are in it. Nudges 2 and 3 are at
        // 02:30Z and 05:30Z (03:30 and 06:30 in Lagos), and the day ends at 23:00Z that evening.
        val ada = users.verified("Ada")
        val bond = bondOf(ada, users.verified("Bea"), LAGOS)
        val member = remind(ada, bond, "23:30", "Atlantic/Reykjavik")
        val lagosDay = DAY.plusDays(1)

        val swept =
            listOf(DAY.at("23:30"), lagosDay.at("02:30"), lagosDay.at("05:30")).map { sweep(member, it).single() }

        swept.map { it.index } shouldBe listOf(1, 2, 3)
        swept.map { it.reminder.bondDate }.toSet() shouldBe setOf(lagosDay)
        swept.map { it.reminder.bondDayEndsAt }.toSet() shouldBe setOf(lagosDay.at("23:00"))
    }

    @Test
    fun `swept every five minutes for three days, no nudge is after its bond-day's end, and each index comes once`() {
        val cases =
            listOf(
                Triple(LONDON, "08:00", LONDON) to 3,
                Triple(LONDON, "20:00", LONDON) to 2,
                Triple(LONDON, "22:00", LONDON) to 1,
                Triple("Asia/Tokyo", "20:00", "America/New_York") to 3,
                Triple(LAGOS, "23:30", "Atlantic/Reykjavik") to 3,
            )
        val members =
            cases.mapIndexed { i, (setting, perDay) ->
                val (anchor, time, zone) = setting
                val user = users.verified("U$i")
                val bond = bondOf(user, users.verified("P$i"), anchor)
                remind(user, bond, time, zone) to perDay
            }
        val ours = members.map { it.first }.toSet()
        val start = DAY.minusDays(1).at("00:00")

        val due =
            generateSequence(start) { it.plus(TICK) }
                .takeWhile { it.isBefore(start.plus(Duration.ofDays(3))) }
                .flatMap { roster.remindersDueBetween(it, it.plus(TICK)) }
                .filter { it.reminder.memberId in ours }
                .toList()

        due.filter { !it.reminder.remindAt.isBefore(it.reminder.bondDayEndsAt) }.shouldBeEmpty()
        due
            .groupBy { Triple(it.reminder.memberId, it.reminder.bondDate, it.index) }
            .values
            .filter { it.size > 1 }
            .shouldBeEmpty()
        // DAY's series lies wholly inside the three days swept (Reykjavik's nudge 1 for it falls on
        // the evening before): each member's count for it is the day rule's.
        members.forEach { (member, perDay) ->
            due
                .filter { it.reminder.memberId == member && it.reminder.bondDate == DAY }
                .map { it.index }
                .sorted() shouldBe (1..perDay).toList()
        }
    }

    // ---- the bond-days a reveal is owed for (C17) ---------------------------

    @Test
    fun `nudge 2 names each of the user's three bonds with its own date, and nudge 1 names none`() {
        // At 10:00Z: 10:00 in London (the 12th), 00:00 in Kiritimati (UTC+14, the 13th), 23:00 in
        // Pago Pago (UTC-11, the 11th).
        val ada = users.verified("Ada")
        val london = bondOf(ada, users.verified("Bea"), LONDON)
        val kiritimati = bondOf(ada, users.verified("Cy"), "Pacific/Kiritimati")
        val pagoPago = bondOf(ada, users.verified("Dee"), "Pacific/Pago_Pago")
        val member = remind(ada, london, "07:00", LONDON)

        sweep(member, DAY.at("07:00")).single().revealDays.shouldBeEmpty()
        val nudge = sweep(member, DAY.at("10:00")).single()

        nudge.index shouldBe 2
        nudge.days() shouldContainExactlyInAnyOrder
            listOf(london to DAY, kiritimati to DAY.plusDays(1), pagoPago to DAY.minusDays(1))
    }

    @Test
    fun `a bond waiting for its second member, and one where the user turned reveals off, are not owed`() {
        val ada = users.verified("Ada")
        val open = bondOf(ada, users.verified("Bea"), LONDON)
        bondOf(ada, null, LONDON)
        val quiet = bondOf(ada, users.verified("Cy"), LONDON)
        jdbc.update(
            """UPDATE bond_members SET notification_prefs = '{"reveal":false}'::jsonb WHERE bond_id = ? AND user_id = ?""",
            quiet,
            ada,
        )
        val member = remind(ada, open, "08:00", LONDON)

        sweep(member, DAY.at("11:00")).single().days() shouldBe listOf(open to DAY)
    }

    @Test
    fun `a paused bond, and one that has not yet begun, are not owed`() {
        val ada = users.verified("Ada")
        val open = bondOf(ada, users.verified("Bea"), LONDON)
        val cy = users.verified("Cy")
        bondOf(ada, cy, LONDON)
        bondOf(ada, users.verified("Dee"), LONDON, began = DAY.at("11:30"))
        spans.suspend(cy, BEGAN)
        val member = remind(ada, open, "08:00", LONDON)

        sweep(member, DAY.at("11:00")).single().days() shouldBe listOf(open to DAY)
        // Nudge 3 falls after the third bond began: now it is owed too.
        sweep(member, DAY.at("14:00")).single().days() shouldHaveSize 2
    }

    // ---- helpers ------------------------------------------------------------

    /** What the roster says is due for [member] in the five minutes up to [upTo]. */
    private fun sweep(
        member: UUID,
        upTo: Instant,
    ): List<DueNudge> = roster.remindersDueBetween(upTo.minus(TICK), upTo).filter { it.reminder.memberId == member }

    private fun DueNudge.days(): List<Pair<UUID, LocalDate>> = revealDays.map { it.bondId to it.bondDate }

    /** Sets [user]'s reminder in [bond]; returns their member id. */
    private fun remind(
        user: UUID,
        bond: UUID,
        time: String,
        zone: String,
    ): UUID {
        jdbc.update(
            "UPDATE bond_members SET reminder_time_local = ?, reminder_timezone = ? WHERE bond_id = ? AND user_id = ?",
            Time.valueOf(LocalTime.parse(time)),
            zone,
            bond,
            user,
        )
        return jdbc.queryForObject("SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?", UUID::class.java, bond, user)!!
    }

    /** A bond anchored in [anchor], `ACTIVE` once [joiner] joins (`PENDING_MEMBER` without one), begun at [began]. */
    private fun bondOf(
        creator: UUID,
        joiner: UUID?,
        anchor: String,
        began: Instant = BEGAN,
    ): UUID {
        val view =
            create.create(
                BondDraft(
                    creator = UserId(creator),
                    type = BondType.FRIENDS,
                    name = "Us",
                    anchorTimezone = RegionZone.of(anchor),
                    revealTimeLocal = null,
                    reminderTimezone = null,
                ),
            )
        joiner?.let { acceptInvite.accept(UserId(it), view.invite!!.invite.code) }
        val bond = view.bond.id.value
        jdbc.update(
            "UPDATE bond_anchor_intervals SET effective_from = ?, first_label = ? WHERE bond_id = ?",
            Timestamp.from(began),
            began.atZone(ZoneId.of(anchor)).toLocalDate(),
            bond,
        )
        return bond
    }

    private fun LocalDate.at(time: String): Instant = atTime(LocalTime.parse(time)).toInstant(java.time.ZoneOffset.UTC)

    private companion object {
        const val LONDON = "Europe/London"
        const val LAGOS = "Africa/Lagos"
        val DAY: LocalDate = LocalDate.parse("2027-01-12")
        val BEGAN: Instant = Instant.parse("2027-01-01T00:00:00Z")
        val TICK: Duration = Duration.ofMinutes(5)
    }
}
