package com.moyi.bond.infra.database

import com.moyi.bond.domain.Block
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
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
    @Autowired private val invites: InviteStore,
    @Autowired private val blocks: BlockStore,
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

        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

        statistics.entityInsertCount shouldBe 3
        statistics.entityLoadCount shouldBe 0
        statistics.prepareStatementCount shouldBe 3
    }

    @Test
    fun `a bond round-trips through the mapper for a member, and does not exist for anyone else`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

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

        transactions.executeWithoutResult { store.insert(furnished).also { invites.insert(spent) } }

        transactions.execute { store.findByMember(furnished.id, furnished.createdBy) } shouldBe furnished
    }

    @Test
    fun `a member who left still finds the bond`() {
        // states.md §9: leaving revokes access to new content, not to the
        // archive. The row stays, and the store keeps answering for it.
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }
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
            store.insert(older).also { invites.insert(olderInvite) }
            store.insert(newer).also { invites.insert(newerInvite) }
            store.insert(theirs).also { invites.insert(theirsInvite) }
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
            store.insert(a).also { invites.insert(ai) }
            store.insert(b).also { invites.insert(bi) }
            store.insert(c).also { invites.insert(ci) }
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
            store.insert(a).also { invites.insert(ai) }
            store.insert(b).also { invites.insert(bi) }
        }
        jdbc.update("UPDATE bond_invites SET revoked_at = now() WHERE id = ?", bi.id.value)

        val live = transactions.execute { invites.findLiveOf(listOf(a.id, b.id), now) }
        live shouldHaveSize 1
        live[a.id] shouldBe ai

        transactions.execute { invites.findLiveOf(listOf(a.id), now.plus(Duration.ofDays(8))) } shouldHaveSize 0
        transactions.execute { invites.findLiveOf(emptyList(), now) } shouldHaveSize 0
    }

    @Test
    fun `V9 refuses a second active membership for one user`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

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
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

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
    // ---- slice B2: the invite lifecycle and blocks ---------------------------

    @Test
    fun `a live invite is found by its code, and a dead one is not`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

        transactions.execute { invites.findLiveByCode(invite.code, now) } shouldBe invite

        // Each of the three ways a row stops being live, one at a time.
        transactions.execute { invites.findLiveByCode(invite.code, now.plus(Invite.TTL)) }.shouldBeNull()
        jdbc.update("UPDATE bond_invites SET revoked_at = now() WHERE id = ?", invite.id.value)
        transactions.execute { invites.findLiveByCode(invite.code, now) }.shouldBeNull()
        jdbc.update("UPDATE bond_invites SET revoked_at = NULL, used_at = now() WHERE id = ?", invite.id.value)
        transactions.execute { invites.findLiveByCode(invite.code, now) }.shouldBeNull()
    }

    @Test
    fun `creating an invite revokes the outstanding one, and only the live ones`() {
        // states.md §2 "Replaced": there is never more than one live code, so
        // a creator who makes a new one knows the old one is dead.
        val (bond, first) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(first) } }
        val spent = first.copy(id = InviteId(ids.timeOrdered()), code = InviteCode("AAAAAA"), usedAt = now)
        transactions.executeWithoutResult { invites.insert(spent) }

        val revoked = transactions.execute { invites.revokeLiveOf(bond.id, now) }

        revoked shouldBe 1
        transactions.execute { invites.findLiveByCode(first.code, now) }.shouldBeNull()
        // The spent one is untouched: it was not live, and its used_at is the record of who joined.
        jdbc.queryForObject("SELECT revoked_at IS NULL FROM bond_invites WHERE id = ?", Boolean::class.java, spent.id.value) shouldBe true
    }

    @Test
    fun `revoking is scoped to its bond, and answers false for anything already dead`() {
        val (bond, invite) = newBond()
        val (other, otherInvite) = newBond()
        transactions.executeWithoutResult {
            store.insert(bond).also { invites.insert(invite) }
            store.insert(other).also { invites.insert(otherInvite) }
        }

        // Another bond's invite id is not this bond's to revoke — the
        // predicate is the authorisation, as everywhere else in this store.
        transactions.execute { invites.revoke(bond.id, otherInvite.id, now) } shouldBe false
        transactions.execute { invites.revoke(bond.id, InviteId(UUID.randomUUID()), now) } shouldBe false

        transactions.execute { invites.revoke(bond.id, invite.id, now) } shouldBe true
        transactions.execute { invites.revoke(bond.id, invite.id, now) } shouldBe false
    }

    @Test
    fun `consuming an invite succeeds exactly once`() {
        // The compare-and-set that stops two people joining a one-seat bond.
        // Two callers both read the invite as live; only one UPDATE matches.
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }
        val joiner = UserId(UUID.randomUUID())

        transactions.execute { invites.consume(invite.id, joiner, now) } shouldBe true
        transactions.execute { invites.consume(invite.id, UserId(UUID.randomUUID()), now) } shouldBe false

        jdbc.queryForObject("SELECT used_by_user_id FROM bond_invites WHERE id = ?", UUID::class.java, invite.id.value) shouldBe
            joiner.value
    }

    @Test
    fun `an expired or revoked invite cannot be consumed`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

        transactions.execute { invites.consume(invite.id, UserId(UUID.randomUUID()), now.plus(Invite.TTL)) } shouldBe false

        jdbc.update("UPDATE bond_invites SET revoked_at = now() WHERE id = ?", invite.id.value)
        transactions.execute { invites.consume(invite.id, UserId(UUID.randomUUID()), now) } shouldBe false
    }

    @Test
    fun `adding a member writes the row and activates the bond in one transaction`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }
        val joiner = Member.member(MemberId(ids.timeOrdered()), bond.id, UserId(UUID.randomUUID()), lagos, now)

        transactions.executeWithoutResult { store.addMember(bond.accept(joiner), joiner) }

        val loaded = transactions.execute { store.findByMember(bond.id, joiner.userId) }.shouldNotBeNull()
        loaded.status shouldBe BondStatus.ACTIVE
        loaded.activeMembers shouldHaveSize 2
        // The version moved, so a stale If-Match from before the join is refused (slice B4).
        loaded.version shouldBe 1
    }

    @Test
    fun `everyone who has ever been a member is listed, including those who left`() {
        // FR-029's block check has to consider the person who walked away:
        // a bond they left is exactly where a block would have been made.
        val (bond, invite) = newBond()
        val joiner = Member.member(MemberId(ids.timeOrdered()), bond.id, UserId(UUID.randomUUID()), lagos, now)
        transactions.executeWithoutResult {
            store.insert(bond).also { invites.insert(invite) }
            store.addMember(bond.accept(joiner), joiner)
        }
        jdbc.update("UPDATE bond_members SET left_at = now() WHERE user_id = ?", joiner.userId.value)

        transactions
            .execute { store.memberUserIdsEverOf(bond.id) }
            .shouldNotBeNull() shouldContainExactlyInAnyOrder listOf(bond.createdBy, joiner.userId)
    }

    @Test
    fun `a block is found from either side`() {
        // FR-029 prevents any future invitation *between* two accounts, and
        // which of them is holding the code is not the rule's business.
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }
        val blocker = bond.createdBy
        val blocked = UserId(UUID.randomUUID())
        val stranger = UserId(UUID.randomUUID())
        transactions.executeWithoutResult { blocks.insert(Block(blocker, blocked, bond.id, now)) }

        transactions.execute { blocks.existsBetween(blocked, listOf(blocker)) } shouldBe true
        transactions.execute { blocks.existsBetween(blocker, listOf(blocked)) } shouldBe true
        transactions.execute { blocks.existsBetween(stranger, listOf(blocker)) } shouldBe false
        transactions.execute { blocks.existsBetween(blocked, listOf(stranger)) } shouldBe false
        transactions.execute { blocks.existsBetween(blocked, emptyList()) } shouldBe false
    }
}
