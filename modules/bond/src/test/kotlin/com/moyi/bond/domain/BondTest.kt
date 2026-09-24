package com.moyi.bond.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The aggregate's invariants (doc 04 §2), each asserted by trying to build an
 * object that breaks it. They are `require`d in the constructor rather than
 * checked by a service, so an invalid `Bond` cannot exist in memory at all —
 * which is what makes "the aggregate enforces its invariants" a fact about
 * the code rather than a convention.
 */
internal class BondTest {
    private val now = Instant.parse("2026-09-24T20:00:00Z")
    private val creator = UserId(UUID.randomUUID())
    private val lagos = RegionZone.of("Africa/Lagos")

    private fun draft(
        name: String = "Us",
        reminderTimezone: RegionZone? = null,
    ) = BondDraft(
        creator = creator,
        type = BondType.COUPLE,
        name = name,
        anchorTimezone = lagos,
        revealTimeLocal = null,
        reminderTimezone = reminderTimezone,
    )

    private fun create(draft: BondDraft = draft()) = Bond.create(BondId(UUID.randomUUID()), MemberId(UUID.randomUUID()), draft, now)

    @Test
    fun `a new bond is pending its second member, with the creator as owner`() {
        val bond = create()

        bond.status shouldBe BondStatus.PENDING_MEMBER
        bond.maxMembers shouldBe 2
        bond.version shouldBe 0
        bond.createdBy shouldBe creator
        bond.strictMode shouldBe false
        bond.activeMembers shouldHaveSize 1

        val owner = bond.activeMembers.single()
        owner.userId shouldBe creator
        owner.role shouldBe MemberRole.OWNER
        owner.joinedAt shouldBe now
        owner.reminderTimeLocal shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `the reminder zone defaults to the anchor zone until the client sets one`() {
        // Spec §11 decision 11: a creator who says nothing gets the bond's own
        // zone, which is right far more often than a fixed default would be.
        create().activeMembers.single().reminderTimezone shouldBe lagos

        val london = RegionZone.of("Europe/London")
        create(draft(reminderTimezone = london)).activeMembers.single().reminderTimezone shouldBe london
    }

    @Test
    fun `memberOf answers for members who left, because the archive stays readable`() {
        // states.md §9 and OQ-09: leaving revokes access to *new* content, not
        // to the archive. So membership has to stay answerable afterwards.
        val bond = create()
        val owner = bond.members.single()
        val afterLeaving = bond.copy(members = listOf(owner.copy(leftAt = now)))

        afterLeaving.memberOf(creator)?.id shouldBe owner.id
        afterLeaving.memberOf(UserId(UUID.randomUUID())).shouldBeNull()
        afterLeaving.activeMembers shouldHaveSize 0
    }

    @Test
    fun `a name is 1 to 60 characters and already trimmed`() {
        shouldThrow<IllegalArgumentException> { create(draft(name = "")) }
        shouldThrow<IllegalArgumentException> { create(draft(name = "   ")) }
        // Trimming is the edge's job; the domain refuses what it is handed
        // rather than quietly rewriting it, so the two cannot disagree.
        shouldThrow<IllegalArgumentException> { create(draft(name = " Us")) }
        shouldThrow<IllegalArgumentException> { create(draft(name = "x".repeat(61))) }

        create(draft(name = "x".repeat(60))).name shouldBe "x".repeat(60)
    }

    @Test
    fun `I-1 - a bond refuses more active members than it has seats`() {
        val bond = create()
        val second = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)
        val third = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)

        bond.copy(members = bond.members + second).activeMembers shouldHaveSize 2
        shouldThrow<IllegalArgumentException> { bond.copy(members = bond.members + second + third) }
    }

    @Test
    fun `a member who left frees their seat`() {
        // The other half of I-1: the limit counts active members, so a bond
        // someone left has room again (and B2's accept will fill it).
        val bond = create()
        val left = bond.members.single().copy(leftAt = now)
        val replacement = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)
        val second = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)

        bond.copy(members = listOf(left, replacement, second)).activeMembers shouldHaveSize 2
    }

    @Test
    fun `a user holds at most one active membership in a bond`() {
        val bond = create()
        val sameUserAgain = Member.member(MemberId(UUID.randomUUID()), bond.id, creator, lagos, now)

        shouldThrow<IllegalArgumentException> { bond.copy(members = bond.members + sameUserAgain) }
    }

    @Test
    fun `a member row must belong to its bond`() {
        val bond = create()
        val elsewhere = Member.member(MemberId(UUID.randomUUID()), BondId(UUID.randomUUID()), UserId(UUID.randomUUID()), lagos, now)

        shouldThrow<IllegalArgumentException> { bond.copy(members = bond.members + elsewhere) }
    }

    @Test
    fun `every type has two seats in v1, and the number comes from the type`() {
        // FR-021 wants the limit to be a property of the type; FR-030 wants a
        // new type to need no migration. Both hold: it is an enum constant.
        BondType.entries.forEach { type -> type.maxMembers shouldBe 2 }
        create(draft().copy(type = BondType.FAMILY)).maxMembers shouldBe 2
    }

    @Test
    fun `accepting a member makes the bond active`() {
        val bond = create()
        val joiner = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)

        val active = bond.accept(joiner)

        active.status shouldBe BondStatus.ACTIVE
        active.activeMembers shouldHaveSize 2
        active.memberOf(joiner.userId)?.role shouldBe MemberRole.MEMBER
    }

    @Test
    fun `only a bond still waiting for someone has room`() {
        val bond = create()
        bond.hasRoom shouldBe true
        bond.isOpen shouldBe true

        val full = bond.accept(Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now))
        full.hasRoom shouldBe false
        // Active, so it still takes writes — it just has no seat.
        full.isOpen shouldBe true
        shouldThrow<IllegalStateException> {
            full.accept(Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now))
        }
    }

    @Test
    fun `an archived bond has no room and takes no writes`() {
        val archived = create().copy(status = BondStatus.ARCHIVED, archivedAt = now)

        archived.hasRoom shouldBe false
        archived.isOpen shouldBe false
        shouldThrow<IllegalStateException> {
            archived.accept(Member.member(MemberId(UUID.randomUUID()), archived.id, UserId(UUID.randomUUID()), lagos, now))
        }
    }

    @Test
    fun `an invite lives seven days and is dead once used, revoked or expired`() {
        val bond = create()
        val invite = Invite.issue(InviteId(UUID.randomUUID()), bond.id, InviteCode("7KQ4MZ"), bond.members.single().id, now)

        invite.expiresAt shouldBe now.plus(Duration.ofDays(7))
        invite.isLive(now) shouldBe true
        invite.isLive(now.plus(Duration.ofDays(7))) shouldBe false
        invite.copy(usedAt = now).isLive(now) shouldBe false
        invite.copy(revokedAt = now).isLive(now) shouldBe false
    }

    @Test
    fun `a nickname is at most 40 characters and never blank`() {
        val member = create().members.single()

        shouldThrow<IllegalArgumentException> { member.copy(nicknameForOther = "") }
        shouldThrow<IllegalArgumentException> { member.copy(nicknameForOther = "x".repeat(41)) }
        member.copy(nicknameForOther = "x".repeat(40)).nicknameForOther shouldBe "x".repeat(40)
        member.copy(nicknameForOther = null).nicknameForOther.shouldBeNull()
    }
}
