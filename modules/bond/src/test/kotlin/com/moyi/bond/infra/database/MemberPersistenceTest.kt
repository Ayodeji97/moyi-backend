package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.MemberSettings
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalTime
import javax.sql.DataSource

/**
 * [MemberStore] against a real Postgres: one member's own settings, written and
 * read back.
 *
 * Its own class rather than more cases in `BondPersistenceTest`, for the reason
 * the store itself is its own class — what is on trial here belongs to **one
 * member and nobody else**, and the thing most worth asserting is what a write
 * to it does *not* touch: the bond row, and therefore the `ETag` the other
 * member is holding (doc 06 §1, `states.md` §8).
 *
 * The same `@SpringBootTest(classes = …)` as the other bond tests, so it shares
 * their cached context and its Hikari pool rather than starting another.
 */
@SpringBootTest(classes = [BondTestApplication::class])
internal class MemberPersistenceTest(
    @Autowired private val members: MemberStore,
    @Autowired private val bonds: BondStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val now = Instant.parse("2026-09-28T20:00:00Z")
    private val lagos = RegionZone.of("Africa/Lagos")

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
    }

    private fun insertedBond(): Bond {
        val draft = BondDraft(UserId(ids.timeOrdered()), BondType.COUPLE, "Us", lagos, null, null)
        val bond = Bond.create(BondId(ids.timeOrdered()), MemberId(ids.timeOrdered()), draft, now)
        transactions.executeWithoutResult { bonds.insert(bond) }
        return bond
    }

    @Test
    fun `a member's settings round-trip, and the bond's version does not move`() {
        val bond = insertedBond()
        val member = bond.members.single()

        transactions.executeWithoutResult {
            members.update(
                member.withSettings(
                    MemberSettings(
                        nicknameForOther = "Ada",
                        reminderTimeLocal = LocalTime.of(7, 30),
                        reminderTimezone = RegionZone.of("Europe/London"),
                        quietHoursStart = LocalTime.of(22, 0),
                        quietHoursEnd = LocalTime.of(7, 0),
                    ),
                ),
            )
        }

        val loaded = transactions.execute { bonds.findByMember(bond.id, bond.createdBy) }.shouldNotBeNull()
        val reloaded = loaded.memberOf(bond.createdBy).shouldNotBeNull()
        reloaded.nicknameForOther shouldBe "Ada"
        reloaded.reminderTimeLocal shouldBe LocalTime.of(7, 30)
        reloaded.reminderTimezone shouldBe RegionZone.of("Europe/London")
        reloaded.quietHoursStart shouldBe LocalTime.of(22, 0)
        reloaded.quietHoursEnd shouldBe LocalTime.of(7, 0)
        // The whole point: a reminder time is not a change to the bond, so the
        // other member's `ETag` is still valid.
        loaded.version shouldBe 0
    }

    @Test
    fun `a replacement clears what it does not carry`() {
        val bond = insertedBond()
        val member = bond.members.single()
        val full =
            MemberSettings(
                nicknameForOther = "Ada",
                reminderTimeLocal = LocalTime.of(7, 30),
                reminderTimezone = RegionZone.of("Europe/London"),
                quietHoursStart = LocalTime.of(22, 0),
                quietHoursEnd = LocalTime.of(7, 0),
            )
        transactions.executeWithoutResult { members.update(member.withSettings(full)) }

        transactions.executeWithoutResult {
            // From the *stored* member, which is what the service loads — the
            // zone is kept relative to the row, not to whatever object a caller
            // happens to be holding.
            val stored =
                bonds
                    .findByMember(bond.id, bond.createdBy)
                    .shouldNotBeNull()
                    .memberOf(bond.createdBy)
                    .shouldNotBeNull()
            members.update(stored.withSettings(MemberSettings(reminderTimeLocal = LocalTime.of(21, 0))))
        }

        val reloaded =
            transactions
                .execute { bonds.findByMember(bond.id, bond.createdBy) }
                .shouldNotBeNull()
                .memberOf(bond.createdBy)
                .shouldNotBeNull()
        reloaded.nicknameForOther.shouldBeNull()
        reloaded.quietHoursStart.shouldBeNull()
        reloaded.quietHoursEnd.shouldBeNull()
        reloaded.reminderTimeLocal shouldBe LocalTime.of(21, 0)
        // Except the zone, which is not the caller's to lose by omission.
        reloaded.reminderTimezone shouldBe RegionZone.of("Europe/London")
    }
}
