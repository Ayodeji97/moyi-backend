package com.moyi.bond.domain

import com.moyi.bond.service.TimezoneChangeTooSoonException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.Locale
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

    @Test
    fun `leaving archives the bond and stamps the member who left`() {
        val bond = create()
        val ownerId = bond.members.single().id
        val later = now.plusSeconds(60)

        val left = bond.leave(ownerId, later)

        left.status shouldBe BondStatus.ARCHIVED
        left.archivedAt shouldBe later
        left.activeMembers shouldHaveSize 0
        left.memberOf(creator)!!.leftAt shouldBe later
    }

    @Test
    fun `the other member stays active when one leaves`() {
        val joined = create().let { it.accept(joiner(it)) }
        val owner = joined.members.first { it.role == MemberRole.OWNER }

        val left = joined.leave(owner.id, now)

        left.status shouldBe BondStatus.ARCHIVED
        left.activeMembers.map { it.role } shouldBe listOf(MemberRole.MEMBER)
    }

    @Test
    fun `an archived bond cannot be left again`() {
        val bond = create()
        val memberId = bond.members.single().id
        val archived = bond.leave(memberId, now)

        shouldThrow<IllegalStateException> { archived.leave(memberId, now) }
    }

    @Test
    fun `ending is idempotent, so blocking twice changes nothing`() {
        val bond = create()
        val memberId = bond.members.single().id
        val ended = bond.end(memberId, now)

        ended.end(memberId, now.plusSeconds(600)) shouldBe ended
    }

    @Test
    fun `ending a bond somebody else already left changes nothing they could see`() {
        // The oracle this closes: the leaver keeps read access to the archive
        // and `MemberResponse` shows both members' `leftAt`, so stamping the
        // blocker's would make the leaver's next GET change — and block is the
        // only mutation an archived bond accepts, so the change could only mean
        // "they blocked me" (doc 26 §2.1, T-09). Found by the Codex bot on #39.
        val joined = create().let { it.accept(joiner(it)) }
        val owner = joined.members.first { it.role == MemberRole.OWNER }
        val other = joined.members.first { it.role == MemberRole.MEMBER }
        val archived = joined.leave(owner.id, now)

        val blocked = archived.end(other.id, now.plusSeconds(3600))

        blocked shouldBe archived
        blocked.archivedAt shouldBe now
        blocked.memberOf(other.userId)!!.leftAt.shouldBeNull()
    }

    @Test
    fun `ending a bond already heading for deletion does not drag it back to archived`() {
        // B5 owns PENDING_DELETION and its cooling-off. Blocking during it must
        // not reset the status the deletion job reads.
        val pending = create().copy(status = BondStatus.PENDING_DELETION)

        pending.end(pending.members.single().id, now).status shouldBe BondStatus.PENDING_DELETION
    }

    @Test
    fun `Strict mode is remembered with the instant it changed, so a past day can be judged by what it was`() {
        val t0 = Instant.parse("2026-09-10T00:00:30Z")
        val bond = create()
        bond.strictMode shouldBe false
        bond.strictModeAt(t0) shouldBe false

        val strict = bond.update(BondSettings(strictMode = true), now = t0)

        strict.strictModeChangedAt shouldBe t0
        // A day that ended half a minute before the change was not a Strict-mode day.
        strict.strictModeAt(t0.minusSeconds(30)) shouldBe false
        strict.strictModeAt(t0) shouldBe true
        strict.strictModeAt(t0.plusSeconds(86_400)) shouldBe true
    }

    @Test
    fun `sending the Strict mode a bond already has is not a change`() {
        val t0 = Instant.parse("2026-09-10T00:00:30Z")
        val strict = create().update(BondSettings(strictMode = true), now = t0)

        val again = strict.update(BondSettings(strictMode = true, name = "Us two"), now = t0.plusSeconds(3_600))

        again.strictModeChangedAt shouldBe t0
        again.strictModeAt(t0.plusSeconds(60)) shouldBe true
    }

    @Test
    fun `updating changes only the fields the caller named`() {
        val bond = create().copy(revealTimeLocal = LocalTime.of(21, 0), strictMode = true)

        val renamed = bond.update(BondSettings(name = "Us two"))

        renamed.name shouldBe "Us two"
        renamed.type shouldBe bond.type
        renamed.revealTimeLocal shouldBe LocalTime.of(21, 0)
        renamed.strictMode shouldBe true
        renamed.version shouldBe bond.version
    }

    @Test
    fun `a named null clears the reveal time, and an absent one leaves it`() {
        // PATCH's one genuine ambiguity: `"revealTimeLocal": null` means clear
        // it, and omitting the field means leave it alone. `Change` is what
        // keeps the two apart all the way down from the wire.
        val bond = create().copy(revealTimeLocal = LocalTime.of(21, 0))

        bond.update(BondSettings(revealTimeLocal = Change(null))).revealTimeLocal.shouldBeNull()
        bond.update(BondSettings(revealTimeLocal = null)).revealTimeLocal shouldBe LocalTime.of(21, 0)
        bond.update(BondSettings(revealTimeLocal = Change(LocalTime.of(7, 30)))).revealTimeLocal shouldBe LocalTime.of(7, 30)
    }

    @Test
    fun `updating a bond that has ended is refused`() {
        val archived = create().let { it.leave(it.members.single().id, now) }

        shouldThrow<IllegalStateException> { archived.update(BondSettings(name = "Us two")) }
    }

    @Test
    fun `changing the type leaves the seats alone`() {
        // FR-021 puts the member limit on the row, and every v1 type seats two.
        // A type change is not the place to move it: a type that ever seats a
        // different number needs a rule about existing members (ADR-0029).
        val bond = create()

        val friends = bond.update(BondSettings(type = BondType.FRIENDS))

        friends.type shouldBe BondType.FRIENDS
        friends.maxMembers shouldBe bond.maxMembers
    }

    @Test
    fun `a name the aggregate would refuse cannot be set by an update either`() {
        // The edge validates too, and this is the layer that cannot be bypassed.
        val bond = create()

        shouldThrow<IllegalArgumentException> { bond.update(BondSettings(name = " ")) }
        shouldThrow<IllegalArgumentException> { bond.update(BondSettings(name = "x".repeat(Bond.MAX_NAME_LENGTH + 1))) }
    }

    @Test
    fun `a member's settings are replaced wholesale`() {
        val member = create().members.single()

        val updated =
            member.withSettings(
                MemberSettings(
                    nicknameForOther = "Ada",
                    reminderTimeLocal = LocalTime.of(7, 0),
                    reminderTimezone = RegionZone.of("Europe/London"),
                    quietHoursStart = LocalTime.of(22, 0),
                    quietHoursEnd = LocalTime.of(7, 0),
                ),
            )

        updated.nicknameForOther shouldBe "Ada"
        updated.reminderTimeLocal shouldBe LocalTime.of(7, 0)
        updated.reminderTimezone shouldBe RegionZone.of("Europe/London")
        updated.quietHoursStart shouldBe LocalTime.of(22, 0)
        updated.quietHoursEnd shouldBe LocalTime.of(7, 0)
        // Identity, role and dates are not in MemberSettings at all, which is
        // stronger than checking them.
        updated.id shouldBe member.id
        updated.userId shouldBe member.userId
        updated.role shouldBe member.role
        updated.joinedAt shouldBe member.joinedAt
        updated.leftAt shouldBe member.leftAt
    }

    @Test
    fun `a PUT clears what it omits, except the zone it would silently reset`() {
        val member =
            create().members.single().withSettings(
                MemberSettings(
                    nicknameForOther = "Ada",
                    reminderTimeLocal = LocalTime.of(7, 0),
                    reminderTimezone = RegionZone.of("Europe/London"),
                    quietHoursStart = LocalTime.of(22, 0),
                    quietHoursEnd = LocalTime.of(7, 0),
                ),
            )

        val replaced = member.withSettings(MemberSettings(reminderTimeLocal = LocalTime.of(20, 0)))

        replaced.nicknameForOther.shouldBeNull()
        replaced.quietHoursStart.shouldBeNull()
        replaced.quietHoursEnd.shouldBeNull()
        // Losing a zone you deliberately set, because you edited a nickname, is
        // the quiet damage doc 04 §6 warns about.
        replaced.reminderTimezone shouldBe RegionZone.of("Europe/London")
    }

    @Test
    fun `one quiet hour without the other is not a state a member can be in`() {
        // A start with no end is not a window, and Phase 4's scheduler would
        // have to invent the other half. V9 permits either column alone, so the
        // aggregate is the layer that says no.
        val member = create().members.single()
        val settings = MemberSettings(reminderTimeLocal = LocalTime.of(20, 0), quietHoursStart = LocalTime.of(22, 0))

        shouldThrow<IllegalArgumentException> { member.withSettings(settings) }
        shouldThrow<IllegalArgumentException> { member.copy(quietHoursEnd = LocalTime.of(7, 0)) }
        // A window that wraps midnight is ordinary and stays legal.
        member.withSettings(settings.copy(quietHoursEnd = LocalTime.of(7, 0))).quietHoursStart shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `the anchor zone can be changed once, and not again for thirty days`() {
        val bond = create()
        val london = RegionZone.of("Europe/London")

        val moved = bond.withAnchorTimezone(london, now)

        moved.anchorTimezone shouldBe london
        moved.timezoneChangedAt shouldBe now
        moved.mayChangeTimezoneAt(now) shouldBe false
        moved.mayChangeTimezoneAt(now.plus(Duration.ofDays(29))) shouldBe false
        moved.mayChangeTimezoneAt(now.plus(Duration.ofDays(30))) shouldBe true
        moved.nextTimezoneChangeAt shouldBe now.plus(Duration.ofDays(30))
        shouldThrow<IllegalStateException> { moved.withAnchorTimezone(lagos, now) }
    }

    @Test
    fun `a bond that has never moved its zone may move it at once`() {
        val bond = create()

        bond.timezoneChangedAt.shouldBeNull()
        bond.mayChangeTimezoneAt(now) shouldBe true
        bond.nextTimezoneChangeAt.shouldBeNull()
    }

    @Test
    fun `an archived bond's anchor zone cannot be moved`() {
        val archived = create().let { it.leave(it.members.single().id, now) }

        shouldThrow<IllegalStateException> { archived.withAnchorTimezone(RegionZone.of("Europe/London"), now) }
    }

    @Test
    fun `requesting deletion starts a thirty-day cooling-off`() {
        val joined = create().let { it.accept(joiner(it)) }

        val pending = joined.requestDeletion(now)

        pending.status shouldBe BondStatus.PENDING_DELETION
        pending.deletionRequestedAt shouldBe now
        pending.deletionScheduledFor shouldBe now.plus(Duration.ofDays(30))
        // Not open, so every other write is refused during it (ADR-0028, ADR-0029)
        // — and cancelling, which ignores `isOpen`, is the one thing that works.
        pending.isOpen shouldBe false
    }

    @Test
    fun `cancelling a deletion returns an active bond to active`() {
        val joined = create().let { it.accept(joiner(it)) }

        val cancelled = joined.requestDeletion(now).cancelDeletion(now)

        cancelled.status shouldBe BondStatus.ACTIVE
        cancelled.deletionRequestedAt.shouldBeNull()
        cancelled.deletionScheduledFor.shouldBeNull()
        cancelled.isOpen shouldBe true
    }

    @Test
    fun `cancelling a deletion does not bring back a bond somebody had left`() {
        // §6.4: the status returns to ARCHIVED if any member has left. A bond
        // does not come back to life because a deletion was called off.
        val joined = create().let { it.accept(joiner(it)) }
        val owner = joined.members.first { it.role == MemberRole.OWNER }
        val archived = joined.leave(owner.id, now)

        val cancelled = archived.copy(status = BondStatus.PENDING_DELETION, deletionRequestedAt = now).cancelDeletion(now)

        cancelled.status shouldBe BondStatus.ARCHIVED
        cancelled.deletionRequestedAt.shouldBeNull()
        // The one path that reaches ARCHIVED without going through `end`, and
        // so the one that used to leave the column null (review of #41).
        cancelled.archivedAt shouldBe now
    }

    @Test
    fun `a bond with no deletion pending has nothing to cancel`() {
        shouldThrow<IllegalStateException> { create().cancelDeletion(now) }
    }

    @Test
    fun `an archived bond cannot be scheduled for deletion`() {
        // ADR-0030: refused whichever way it ended, so a blocked member cannot
        // tell a block from a leave by trying it (doc 26 §2.1).
        val left = create().let { it.leave(it.members.single().id, now) }

        shouldThrow<IllegalStateException> { left.requestDeletion(now) }
    }

    @Test
    fun `cancelling a deletion on a bond still waiting returns it to PENDING_MEMBER`() {
        // The review of PR #41: restoring ACTIVE here orphans the bond
        // permanently — `hasRoom` requires PENDING_MEMBER, so the invite it
        // still advertises could never be used, and doc 04 §8.3a wants no
        // Bond-days opened while a bond waits.
        val solo = create()

        val cancelled = solo.requestDeletion(now).cancelDeletion(now)

        cancelled.status shouldBe BondStatus.PENDING_MEMBER
        cancelled.hasRoom shouldBe true
        cancelled.deletionRequestedAt.shouldBeNull()
    }

    @Test
    fun `a member may leave during the cooling-off, and the deletion stays scheduled`() {
        // Refusing for thirty days would make FR-029's protection depend on what
        // the other person agreed to a fortnight earlier — and refusing `leave`
        // while permitting `block` would make the two distinguishable, which is
        // the oracle doc 26 §2.1 forbids.
        val joined = create().let { it.accept(joiner(it)) }
        val owner = joined.members.first { it.role == MemberRole.OWNER }
        val pending = joined.requestDeletion(now)

        pending.canBeEnded shouldBe true
        val left = pending.leave(owner.id, now.plusSeconds(60))

        left.memberOf(owner.userId)!!.leftAt shouldBe now.plusSeconds(60)
        // The status does **not** change: both agreed to destroy this bond, and
        // one of them walking away is not a reason to undo that.
        left.status shouldBe BondStatus.PENDING_DELETION
        left.deletionScheduledFor shouldBe now.plus(Duration.ofDays(30))
    }

    @Test
    fun `cancelling after somebody left during the cooling-off cannot revive the bond`() {
        // The second half of the review's finding: without the `leftAt` stamp
        // above, the other member's cancel would return this bond to ACTIVE with
        // a blocker still inside it.
        val joined = create().let { it.accept(joiner(it)) }
        val owner = joined.members.first { it.role == MemberRole.OWNER }
        val ended = joined.requestDeletion(now).end(owner.id, now)

        val cancelled = ended.cancelDeletion(now)

        cancelled.status shouldBe BondStatus.ARCHIVED
        cancelled.archivedAt.shouldNotBeNull()
        cancelled.isOpen shouldBe false
    }

    @Test
    fun `ending a bond that is already archived still changes nothing`() {
        // The B3 property the wider `canBeEnded` must not break.
        val archived = create().let { it.leave(it.members.single().id, now) }

        archived.end(archived.members.single().id, now.plusSeconds(600)) shouldBe archived
        shouldThrow<IllegalStateException> { archived.leave(archived.members.single().id, now) }
    }

    @Test
    fun `the too-soon refusal names an English date whatever the server's locale is`() {
        // The review of PR #41: `MMMM` resolves against the JVM's default
        // locale, so this API would have answered "28 octobre 2026" on a
        // container whose locale happened to be French — output that varies with
        // the deployment rather than with anything the client sent.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val refusal = TimezoneChangeTooSoonException(Instant.parse("2026-10-28T09:00:00Z"), lagos)

            // The 29th, not the 28th: see the rounding test below.
            refusal.detail shouldBe "The shared time zone can change again from 29 October 2026."
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the too-soon refusal names the first date the change is allowed all day`() {
        // The second review of PR #41. Thirty days from an afternoon lands in an
        // afternoon, so naming *that* calendar date makes the sentence false for
        // most of the day it names — a client reading "28 October" and retrying
        // at 10:00 on the 28th got the identical refusal naming the identical
        // date. Rounding up is the honest direction to be wrong in: the answer
        // may arrive sooner than promised, never later.
        val midMorning = TimezoneChangeTooSoonException(Instant.parse("2026-10-28T09:00:00Z"), lagos)
        midMorning.detail shouldBe "The shared time zone can change again from 29 October 2026."

        // Exactly midnight in the bond's own zone is already a whole day, so it
        // is not pushed out by one. Africa/Lagos is UTC+1.
        val midnightInLagos = TimezoneChangeTooSoonException(Instant.parse("2026-10-27T23:00:00Z"), lagos)
        midnightInLagos.detail shouldBe "The shared time zone can change again from 28 October 2026."
    }

    private fun joiner(bond: Bond): Member = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)
}
