package com.moyi.bond.api

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * What another module may know about a caller's place in a bond (ADR-0026's
 * pattern, second use). `identity.api.UserDirectory` answers "who is this
 * person"; this answers "may they write here, and by whose calendar".
 *
 * **[BondMembership]'s constructor is `internal`, and that is the mechanism.**
 * ADR-0026 made `bond.service.Membership` a value only `BondAccessGuard` can
 * construct, held by two Konsist rules. Kotlin's `internal` is scoped to the
 * Gradle module, so a caller outside `bond` can hold one of these, read it and
 * pass it down — and cannot forge one. The guarantee crosses the module
 * boundary as a compiler error rather than as a rule somebody has to write and
 * somebody else has to not forget. `UserDirectory` needed nothing like this: a
 * display name is not an authorisation decision.
 */
interface BondAccess {
    /**
     * @throws com.moyi.common.web.NotFoundException the caller holds no
     * membership row in [bondId], or there is no such bond — one answer for
     * both, because a 403 would confirm the bond is real (doc 06 §2, T-02).
     */
    fun membershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership

    /**
     * The caller's membership, read **under the bond's own row lock**, which
     * this method takes and the caller's transaction holds until commit.
     *
     * `MANDATORY` propagation, not `REQUIRED`: a caller without a transaction
     * would take a lock that is released the instant this method returns,
     * which looks identical to working and protects nothing. Failing loudly is
     * the only honest option (spec §2.1).
     *
     * **The lock order across this application is bond, then bond-day, then
     * entry**, on submission, editing, lifecycle reconciliation and closing.
     * The close job takes the same bond lock through [lockClosingViewOf]
     * before it locks a day, so a close sees committed pairing and timezone
     * changes and cannot reverse the writer lock order.
     * `ChangeTimezone`, `EndBond`, `RequestDeletion`, `UpdateBond`,
     * `CreateInvite`, `RevokeInvite`, `AcceptInvite` and `MemberSettingsService`
     * already take this same lock first, which is what makes a gratitude write
     * serialise against leave, block, deletion and zone confirmation rather
     * than merely check `isOpen` and hope. Checking `isOpen` and then taking a
     * separate day lock would let a write commit after the bond ended.
     *
     * @throws com.moyi.common.web.NotFoundException as [membershipOf]
     * @throws org.springframework.transaction.IllegalTransactionStateException no transaction
     */
    fun lockMembershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership

    /**
     * A bond's calendar and lifecycle instants **for a caller that is nobody's
     * user** — the close job (spec §6.4, ADR-0031 "Owed, C3"). `null` when
     * there is no such bond.
     *
     * [membershipOf] and [lockMembershipOf] both begin with the membership
     * guard, because they answer a request and a request has a caller. The
     * closer has none: it settles days for every bond, on a timer. So this
     * runs no guard, and that is the whole reason it exists — **which makes
     * it the one method here that must never be reachable from a request.**
     * It discloses nothing of a bond but dates and a time, and it is still
     * not to be called from a controller: `ArchitectureTest` holds that.
     *
     * No lock (ADR-0031 decision 18): the closer starts at the bond-day and
     * never holds the bond's row.
     */
    fun closingViewOf(bondId: UUID): BondClosingView?

    /**
     * The closer's view, after taking the bond lock in the current
     * transaction. The closer takes this before a Bond-day lock, preserving
     * the application's bond -> bond-day order while it settles a day.
     */
    fun lockClosingViewOf(bondId: UUID): BondClosingView?

    /**
     * The ids of bonds that have ever had two members, in id order, after
     * [after] — a keyset page for the close job's walk over every calendar
     * that can be missing a day. A bond that has only ever had its creator is
     * left out: doc 04 §8.3a creates no days for it but the ones its creator
     * writes on. A bond that has since ended is kept: the days before it
     * ended still have to be settled (spec §6.4).
     */
    fun bondsToSweep(
        after: UUID?,
        limit: Int,
    ): List<UUID>
}

/**
 * A caller's place in a bond, and the five facts about the bond that a write
 * outside this module needs: whose calendar decides the day (doc 04 §6), when
 * a day reveals (FR-062), whether freezes are off (FR-073), whether the bond
 * takes writes at all (BR-9), and whether it is still waiting for a second
 * member (doc 04 §8.3a, `gratitude`'s own use — [awaitingSecondMember]'s own
 * KDoc has the reason).
 *
 * [hasLeft] is carried rather than withheld, for the reason `Membership` gives:
 * `states.md` §9 keeps the archive readable after a bond ends. **A caller must
 * check it.** Do not assume `isOpen` covers it — that assumption is exactly
 * what the second review of PR #41 found in `RequestDeletion.cancel`.
 *
 * Twelve fields, not seven arguments to reorder by accident: every one is
 * named at every call site (`BondAccessAdapter`'s one projection, shared by
 * [BondAccess.membershipOf] and [BondAccess.lockMembershipOf]), and the shape
 * is the bond facts above plus the caller's own identifiers, [activeSince],
 * [endedAt] and [anchorTimeline] — splitting it into a nested value would just
 * move the count, not reduce it.
 */
@Suppress("LongParameterList")
class BondMembership internal constructor(
    val bondId: UUID,
    val memberId: UUID,
    val userId: UUID,
    val anchorTimezone: String,
    val revealTimeLocal: LocalTime?,
    val strictMode: Boolean,
    val isOpen: Boolean,
    val hasLeft: Boolean,
    /**
     * True while this bond has only its creator (`BondStatus.PENDING_MEMBER`),
     * false once a second member has accepted an invite. Added for
     * `gratitude`'s `POST /bonds/{bondId}/entries` (doc 04 §8.3a, as the
     * Phase 3 design §12.4 resolves it): the creator may write before their
     * partner joins (`02` J1), and the Bond-day that write lands on has to
     * open `SUSPENDED` rather than `OPEN` so the close job and the streak
     * walk both leave it alone. [isOpen] cannot answer this — it is `true`
     * for both `PENDING_MEMBER` and `ACTIVE` — so this is a fifth bond fact
     * rather than a reinterpretation of one already here.
     */
    val awaitingSecondMember: Boolean,
    /**
     * When the bond became `ACTIVE` — the **second** member's
     * `bond_members.joined_at`, not `bonds.created_at`. Null while the bond is
     * still `PENDING_MEMBER`.
     *
     * The distinction is load-bearing for the close job, which writes missing
     * days from activation forward: starting at `created_at` instead would
     * manufacture `EMPTY` days across the whole waiting window, which is
     * precisely the harm doc 04 §8.3a's `SUSPENDED` resolution exists to
     * prevent. There is no activation column on `bonds`; this is derived.
     */
    val activeSince: Instant?,
    /** When the bond ended (`bonds.archived_at`), or null while it is live. */
    val endedAt: Instant?,
    /**
     * The zone that **decides dates** over time, which is not
     * [anchorTimezone] — that is the zone the bond currently *requests*. They
     * differ for up to one logical day after a change is confirmed (BR-6).
     */
    val anchorTimeline: BondAnchorTimeline,
) {
    /** Ids only — a bond's name is the couple's words (doc 18 §9). */
    override fun toString(): String = "BondMembership(bondId=$bondId, memberId=$memberId)"
}

/**
 * What the close job may know of a bond ([BondAccess.closingViewOf]): when it
 * became two people, when it ended, when its days reveal, and the timeline
 * its days are cut from. No member, no user, no name — the closer acts for
 * nobody, and a type that cannot carry a member cannot be mistaken for
 * permission to act as one.
 */
class BondClosingView internal constructor(
    val bondId: UUID,
    /** As [BondMembership.activeSince]: the second member's join, `null` while the bond waits for one. */
    val activeSince: Instant?,
    /**
     * When the bond stopped taking writes: when it was archived, or — while
     * it is counting down to deletion (`PENDING_DELETION`) — when deletion
     * was requested. Wider than [BondMembership.endedAt], on purpose: spec
     * §6.4 writes no day for "deletion or archived intervals", and a bond in
     * its cooling-off refuses every entry, so a day in that month is not one
     * the couple missed. **If the deletion is called off this is `null`
     * again**: nothing records that the interval happened, and the days in
     * it are then written as missed (ADR-0033, open with the owner).
     */
    val endedAt: Instant?,
    /** The bond's **current** reveal time (FR-062) — what a `PENDING_REVEAL` day is waiting for. */
    val revealTimeLocal: LocalTime?,
    val anchorTimeline: BondAnchorTimeline,
) {
    override fun toString(): String = "BondClosingView(bondId=$bondId)"
}

/**
 * The UTC span `[startsAt, endsAt)` of one Bond-day, and whether it is the
 * degenerate, empty one an eastward anchor move can produce — a projection of
 * `bond.domain.DayBounds` for a caller outside this module (spec §2.1).
 *
 * `internal constructor`, the same mechanism as [BondMembership] and
 * [BondAnchorTimeline]: `gratitude` may hold and read one of these but cannot
 * build one from parts it has no business asserting about the domain.
 */
class BondDayBounds internal constructor(
    val date: LocalDate,
    val startsAt: Instant,
    val endsAt: Instant,
    val isDegenerate: Boolean,
)

/**
 * `bond`'s effective-zone history, as much of it as another module may ask
 * about — a projection of `bond.domain.AnchorTimeline`, answering the same
 * four questions (and saying where it begins), rather than that type itself: the domain type is `internal`
 * to `bond`, and keeping it that way is what stops `gratitude` from reaching
 * `bond`'s private tables (spec §2.1).
 *
 * **Holds closures, not the domain `AnchorTimeline` object.** A reference to
 * `bond.domain.AnchorTimeline` cannot appear in a file under `bond.api`:
 * `ArchitectureTest`'s "layers only depend inwards" rule treats `api` the same
 * as `domain` — depending on nothing within its own module — precisely so a
 * caller in `gratitude` can never drag `bond`'s internals in through this
 * type. `BondAccessAdapter`, in `bond.service` (which *may* import `domain`),
 * is the only place that builds one, closing over the real timeline so every
 * question is still answered by the one implementation of the date
 * arithmetic, in the domain; this type only forwards to it.
 *
 * The constructor is `internal` for ADR-0026's reason, unchanged: a caller
 * outside `bond` can hold one, read it and pass it down, and cannot forge one.
 */
class BondAnchorTimeline internal constructor(
    /**
     * The first instant the timeline covers — the bond's creation, where its
     * first interval begins. Every question below is answerable from here on
     * and from nowhere earlier: an instant before it has no day on this
     * bond's calendar, because the bond did not exist. `gratitude` uses it to
     * refuse an offline draft claiming a time before then (BR-3a) rather than
     * asking the questions below of an instant they cannot answer.
     */
    val beginsAt: Instant,
    private val zoneIdAtFn: (Instant) -> String,
    private val dateAtFn: (Instant) -> LocalDate,
    private val dayBoundsAtFn: (Instant) -> BondDayBounds,
    private val usedLabelsUpToFn: (Instant) -> Set<LocalDate>,
) {
    fun zoneIdAt(at: Instant): String = zoneIdAtFn(at)

    fun dateAt(at: Instant): LocalDate = dateAtFn(at)

    /** The UTC span `[startsAt, endsAt)` of the logical day containing [at]. */
    fun dayBoundsAt(at: Instant): BondDayBounds = dayBoundsAtFn(at)

    /** Every calendar label this bond has issued up to [now]. */
    fun usedLabelsUpTo(now: Instant): Set<LocalDate> = usedLabelsUpToFn(now)
}
