package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.Random
import java.util.UUID
import javax.sql.DataSource

/**
 * The boundary tests for bond persistence: real Postgres, real Flyway, real
 * Hibernate (doc 12: prefer real-Postgres integration tests over mocks).
 * Three things are on trial that a unit test cannot reach — V9's constraints,
 * the statements Hibernate actually emits, and whether the mapping agrees
 * with the schema at all.
 *
 * That last one needs no test of its own: `ddl-auto: validate` in this
 * module's test `application.yml` means the context does not start unless
 * every mapped column exists with a compatible type.
 */
@SpringBootTest(classes = [BondTestApplication::class])
internal class BondPersistenceTest(
    @Autowired private val store: BondStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired entityManagerFactory: EntityManagerFactory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val now = Instant.parse("2026-09-24T20:00:00Z")
    private val lagos = RegionZone.of("Africa/Lagos")

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
    }

    private fun newBond(
        creator: UserId = UserId(UUID.randomUUID()),
        createdAt: Instant = now,
    ): Pair<Bond, Invite> {
        val draft = BondDraft(creator, BondType.COUPLE, "Us", lagos, null, null)
        val bond = Bond.create(BondId(ids.timeOrdered()), MemberId(ids.timeOrdered()), draft, createdAt)
        val invite =
            Invite.issue(
                InviteId(ids.timeOrdered()),
                bond.id,
                InviteCode.random(Random(bond.id.value.leastSignificantBits)),
                bond.members.single().id,
                createdAt,
            )
        return bond to invite
    }

    @Test
    fun `inserting a bond with assigned ids costs one statement per row, with no SELECT`() {
        // The reason these entities implement Persistable, as UserEntity does:
        // without it `save()` asks merge() to look for a row that cannot exist,
        // and every bond ever created pays for a SELECT that always misses.
        val (bond, invite) = newBond()
        statistics.clear()

        transactions.executeWithoutResult { store.insert(bond, invite) }

        statistics.entityInsertCount shouldBe 3
        statistics.entityLoadCount shouldBe 0
        statistics.prepareStatementCount shouldBe 3
    }

    @Test
    fun `a bond round-trips through the mapper for a member, and does not exist for anyone else`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond, invite) }

        transactions.execute { store.findByMember(bond.id, bond.createdBy) } shouldBe bond

        // The repository-level half of doc 05 §5.5: there is no way to ask for
        // a bond without saying who is asking, so a stranger's read returns
        // nothing rather than something the layer above must remember to hide.
        transactions.execute { store.findByMember(bond.id, UserId(UUID.randomUUID())) }.shouldBeNull()
        transactions.execute { store.findByMember(BondId(UUID.randomUUID()), bond.createdBy) }.shouldBeNull()
    }

    @Test
    fun `every nullable column survives the round trip, set rather than left null`() {
        // The columns the happy path leaves null are exactly the ones a mapper
        // can silently drop, so they are set here on purpose.
        val (bond, invite) = newBond()
        val furnished =
            bond.copy(
                revealTimeLocal = LocalTime.of(21, 0),
                timezoneChangedAt = now.minus(Duration.ofDays(40)),
                strictMode = true,
                members =
                    bond.members.map {
                        it.copy(
                            nicknameForOther = "Moyi",
                            quietHoursStart = LocalTime.of(22, 30),
                            quietHoursEnd = LocalTime.of(7, 0),
                            reminderTimeLocal = LocalTime.of(19, 15),
                            reminderTimezone = RegionZone.of("Europe/London"),
                        )
                    },
            )
        val spent = invite.copy(usedAt = now, usedByUserId = UserId(UUID.randomUUID()), revokedAt = now)

        transactions.executeWithoutResult { store.insert(furnished, spent) }

        transactions.execute { store.findByMember(furnished.id, furnished.createdBy) } shouldBe furnished
    }

    @Test
    fun `a member who left still finds the bond`() {
        // states.md §9: leaving revokes access to new content, not to the
        // archive. The row stays, and the store keeps answering for it.
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond, invite) }
        jdbc.update("UPDATE bond_members SET left_at = now() WHERE bond_id = ?", bond.id.value)

        val loaded = transactions.execute { store.findByMember(bond.id, bond.createdBy) }.shouldNotBeNull()

        loaded.activeMembers shouldBe emptyList()
        loaded.memberOf(bond.createdBy).shouldNotBeNull()
    }

    @Test
    fun `my bonds are every bond I hold a membership row in, newest first`() {
        val me = UserId(UUID.randomUUID())
        val (older, olderInvite) = newBond(creator = me, createdAt = now.minus(Duration.ofDays(1)))
        val (newer, newerInvite) = newBond(creator = me)
        val (theirs, theirsInvite) = newBond()
        transactions.executeWithoutResult {
            store.insert(older, olderInvite)
            store.insert(newer, newerInvite)
            store.insert(theirs, theirsInvite)
        }

        transactions.execute { store.findAllByMember(me) }.map { it.id } shouldContainExactly listOf(newer.id, older.id)
        transactions.execute { store.findAllByMember(UserId(UUID.randomUUID())) } shouldBe emptyList()
    }

    @Test
    fun `open bonds are the PENDING_MEMBER and ACTIVE ones I have not left`() {
        // FR-025 counts what a person can still be written to in, so an
        // archived bond and one they walked away from do not stand in the way
        // of starting another.
        val me = UserId(UUID.randomUUID())
        val (a, ai) = newBond(creator = me)
        val (b, bi) = newBond(creator = me)
        val (c, ci) = newBond(creator = me)
        transactions.executeWithoutResult {
            store.insert(a, ai)
            store.insert(b, bi)
            store.insert(c, ci)
        }

        transactions.execute { store.countOpenBondsOf(me) } shouldBe 3

        jdbc.update("UPDATE bonds SET status = 'ARCHIVED', archived_at = now() WHERE id = ?", a.id.value)
        jdbc.update("UPDATE bond_members SET left_at = now() WHERE bond_id = ?", b.id.value)

        transactions.execute { store.countOpenBondsOf(me) } shouldBe 1
    }

    @Test
    fun `live invites are keyed by bond and exclude the used, the revoked and the expired`() {
        val (a, ai) = newBond()
        val (b, bi) = newBond()
        transactions.executeWithoutResult {
            store.insert(a, ai)
            store.insert(b, bi)
        }
        jdbc.update("UPDATE bond_invites SET revoked_at = now() WHERE id = ?", bi.id.value)

        val live = transactions.execute { store.findLiveInvites(listOf(a.id, b.id), now) }
        live shouldHaveSize 1
        live[a.id] shouldBe ai

        transactions.execute { store.findLiveInvites(listOf(a.id), now.plus(Duration.ofDays(8))) } shouldHaveSize 0
        transactions.execute { store.findLiveInvites(emptyList(), now) } shouldHaveSize 0
    }

    @Test
    fun `V9 refuses a second active membership for one user`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond, invite) }

        val refused =
            shouldThrow<DataIntegrityViolationException> {
                jdbc.update(
                    "INSERT INTO bond_members (id, bond_id, user_id, role, joined_at, reminder_timezone) " +
                        "VALUES (?, ?, ?, 'MEMBER', now(), 'Africa/Lagos')",
                    UUID.randomUUID(),
                    bond.id.value,
                    bond.createdBy.value,
                )
            }

        refused.message!!.contains("bond_members_active_key") shouldBe true
    }

    @Test
    fun `V9 refuses a duplicate invite code, a name that is too long and a code outside the alphabet`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond, invite) }

        shouldThrow<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO bond_invites (id, bond_id, code, created_by_member_id, expires_at) VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(),
                bond.id.value,
                invite.code.value,
                bond.members
                    .single()
                    .id.value,
            )
        }.message!!.contains("bond_invites_code_key") shouldBe true

        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE bonds SET name = ? WHERE id = ?", "x".repeat(61), bond.id.value)
        }.message!!.contains("bonds_name_length_check") shouldBe true

        // The alphabet, in the schema: the last line of defence for T-06 after
        // the domain type and (from B2) the rate limits.
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO bond_invites (id, bond_id, code, created_by_member_id, expires_at) VALUES (?, ?, 'ABC0DE', ?, now())",
                UUID.randomUUID(),
                bond.id.value,
                bond.members
                    .single()
                    .id.value,
            )
        }.message!!.contains("bond_invites_code_shape_check") shouldBe true
    }
}
