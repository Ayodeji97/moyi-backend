package com.moyi.bond.domain

import java.time.Instant
import java.time.LocalTime

/**
 * What kind of relationship this is (ADR-0003, ADR-0013).
 *
 * FR-021 wants the member limit to be "a configurable property of the Bond
 * type, not a hardcoded constant", and FR-030 wants new types to need no
 * schema migration. Both hold here: the number hangs off the enum constant,
 * and a new type is a new constant plus a widened CHECK in a migration that
 * moves no data.
 *
 * Every type is two people in v1. `PARENT_CHILD` is in the enum and no card
 * selects it — ADR-0013's amendment replaced it with `FAMILY`, which is right
 * for siblings and cousins, and left the older value in place because
 * removing an enum value is the only expensive kind of change here.
 */
internal enum class BondType(
    val maxMembers: Int,
) {
    COUPLE(2),
    FRIENDS(2),
    FAMILY(2),
    PARENT_CHILD(2),
}

/**
 * Doc 04 §4.3's state machine. B1 only creates, so only [PENDING_MEMBER] is
 * reachable from here; the transitions arrive with the slices that own them —
 * `ACTIVE` with B2's accept, `ARCHIVED` with B3's leave and block,
 * `PENDING_DELETION` with B5, and `DELETED` with the Phase 5 job.
 */
internal enum class BondStatus { PENDING_MEMBER, ACTIVE, ARCHIVED, PENDING_DELETION, DELETED }

/**
 * What `POST /bonds` asks for (doc 06 §3.3), already validated at the edge —
 * the value types cannot hold anything the domain would refuse, so the
 * service has no failure mode left to handle.
 */
internal data class BondDraft(
    val creator: UserId,
    val type: BondType,
    val name: String,
    val anchorTimezone: RegionZone,
    val revealTimeLocal: LocalTime?,
    /**
     * The creator's own zone for reminders. `null` means "use the anchor zone
     * for now" (spec §11 decision 11) — a client that knows the device's zone
     * sends it, and one that does not gets the answer that is right more often
     * than any fixed default.
     */
    val reminderTimezone: RegionZone?,
)

/**
 * A value the caller **named**, which may itself be `null`.
 *
 * `PATCH` has one genuine ambiguity and this is the answer to it: a field the
 * client omitted must keep its current value, and a field the client sent as
 * `null` must be cleared. A plain nullable parameter cannot tell those apart,
 * and guessing is how a client's untouched setting gets wiped by a request
 * about something else.
 *
 * Only nullable settings need one, so `revealTimeLocal` is the only field that
 * has one today. Slice B5's proposals will want it again.
 */
internal data class Change<T : Any>(
    val value: T?,
)

/** [Change]'s whole purpose, as one readable expression: named wins, absent keeps. */
internal fun <T : Any> Change<T>?.orKeep(current: T?): T? = if (this != null) value else current

/**
 * The fields `PATCH /bonds/{bondId}` may change (doc 06 §3.3, ADR-0013 §8).
 *
 * `null` means "not named" for every property; [revealTimeLocal] is a [Change]
 * because it is the one that can also be set *to* null.
 *
 * **`anchorTimezone` is deliberately absent.** FR-027 makes it two-party and at
 * most once per 30 days, which is slice B5's `PATCH /bonds/{bondId}/timezone`.
 * The web layer refuses a body that names it rather than ignoring it, because a
 * silently dropped setting reports success for a change that never happened.
 */
internal data class BondSettings(
    val name: String? = null,
    val type: BondType? = null,
    val revealTimeLocal: Change<LocalTime>? = null,
    val strictMode: Boolean? = null,
)

/**
 * The aggregate root (ADR-0003): a relationship between a small number of
 * people, generic over what kind of relationship it is.
 *
 * Immutable — a state change is a method returning a new `Bond`, as `User` is
 * in identity. The invariants doc 04 §2 assigns to this aggregate are
 * `require`d in the constructor, so an object that breaks one cannot exist:
 *
 * - **I-1** at most [maxMembers] active members, and no user twice.
 * - **I-5 / BR-9** an archived bond accepts no writes — enforced by the
 *   transition methods: [accept] and [leave] refuse one, and [end] is the
 *   single deliberate exception, because FR-029 allows a block after the bond
 *   has ended (ADR-0028).
 *
 * [members] includes people who have **left**. That is deliberate:
 * `states.md` §9 keeps the archive readable for both members afterwards, so
 * "is this person a member of this bond" has to stay answerable for them, and
 * [activeMembers] is the narrower question.
 */
internal data class Bond(
    val id: BondId,
    val type: BondType,
    val name: String,
    val anchorTimezone: RegionZone,
    val timezoneChangedAt: Instant?,
    val revealTimeLocal: LocalTime?,
    val strictMode: Boolean,
    val status: BondStatus,
    val maxMembers: Int,
    val createdBy: UserId,
    val createdAt: Instant,
    val archivedAt: Instant?,
    val deletionRequestedAt: Instant?,
    /** The row version behind the `ETag` (doc 06 §1). `0` until the first update; B4 compares it. */
    val version: Int,
    val members: List<Member>,
) {
    init {
        // Mirrors V9's CHECKs, for the reason given there: the database
        // constraint cannot be bypassed, this one can explain itself.
        require(name.isNotBlank() && name.trim() == name && name.length <= MAX_NAME_LENGTH) {
            "a bond name must be between 1 and $MAX_NAME_LENGTH characters, already trimmed"
        }
        require(maxMembers >= MIN_MEMBERS) { "a bond has at least $MIN_MEMBERS seats" }
        require(members.all { it.bondId == id }) { "every member row must belong to this bond" }
        require(activeMembers.size <= maxMembers) { "a bond has at most $maxMembers active members (I-1)" }
        require(activeMembers.distinctBy { it.userId }.size == activeMembers.size) {
            "a user holds at most one active membership in a bond"
        }
    }

    /** The people currently in it. The pair, once someone has joined. */
    val activeMembers: List<Member> get() = members.filter(Member::isActive)

    /**
     * Everyone who has ever held a membership row here, those who have left
     * included — which is who FR-029's block check has to consider: a bond
     * somebody walked away from is exactly where a block would have been made.
     *
     * On the aggregate rather than in the store (where it was until B4) because
     * every caller has already loaded the bond with its members, so the query it
     * used to make was a second trip for data in hand.
     */
    fun everyMemberUserId(): List<UserId> = members.map { it.userId }.distinct()

    /** This user's membership, current or ended; `null` for someone who was never in it. */
    fun memberOf(userId: UserId): Member? = members.firstOrNull { it.userId == userId }

    /**
     * Still waiting for someone, with a seat free (I-1). The question
     * `POST /invites/{code}/accept` asks, and the answer is deliberately
     * *not* visible to the caller when it is `false`: a full bond and a
     * revoked code are one indistinguishable refusal (FR-024).
     */
    val hasRoom: Boolean get() = status == BondStatus.PENDING_MEMBER && activeMembers.size < maxMembers

    /**
     * Takes writes at all. `ARCHIVED` and `PENDING_DELETION` do not (I-5,
     * BR-9): a bond that has ended is a record, and the only things it still
     * accepts are reads, export and deletion.
     */
    val isOpen: Boolean get() = status == BondStatus.PENDING_MEMBER || status == BondStatus.ACTIVE

    /**
     * The second member joins (FR-022).
     *
     * The bond becomes `ACTIVE`, and that is what starts the clock rather than
     * bookkeeping: doc 04 §8.3a suspends Bond-day evaluation entirely while a
     * bond is `PENDING_MEMBER`, so a creator who writes before their partner
     * arrives — which `02` J1 requires they can — accumulates no solo days and
     * loses no streak that never began. The streak starts here.
     *
     * `check`, not `require`: a bond with no seat is a state conflict rather
     * than a bad argument, and the service turns it into the one 404 that
     * tells a stranger nothing about why (FR-024).
     */
    fun accept(member: Member): Bond {
        check(hasRoom) { "a bond that is not waiting for a member cannot accept one" }
        return copy(status = BondStatus.ACTIVE, members = members + member)
    }

    /**
     * FR-026: this member walks away, and the bond becomes a record.
     *
     * One member leaving archives the whole bond rather than leaving the other
     * alone in it. Doc 04 §4.3 has no state for a bond of one and the product
     * has no screen for it: a gratitude exchange between two people is over
     * when either of them stops, and `states.md` §9 keeps what was written
     * readable for both afterwards instead of pretending the bond continues.
     *
     * Nobody is notified — T-09's "discreet exit". The other member finds out
     * by opening the app, which is also how they would find out about a block,
     * and that is the point (doc 26 §2.1).
     *
     * `check`, not `require`: leaving a bond that has already ended is a state
     * conflict, and the service turns it into `409 BOND_ARCHIVED`.
     */
    fun leave(
        memberId: MemberId,
        now: Instant,
    ): Bond {
        check(isOpen) { "a bond that has ended cannot be left again" }
        return end(memberId, now)
    }

    /**
     * [leave]'s effect on a bond that is still open, and **nothing at all** on
     * one that has already ended — what FR-029's block does, and the reason it
     * is a separate function.
     *
     * Block is permitted on an archived bond, because blocking someone who left
     * first is exactly the case FR-029 exists for (ADR-0028). On that bond this
     * returns `this`, and that is the whole of doc 26 §2.1 in one line.
     *
     * **The reason, because it is not obvious and it was nearly wrong.** After
     * one member leaves, their `left_at` is stamped and the other's is null, and
     * the leaver keeps read access to the archive (`states.md` §9) where
     * `MemberResponse` shows both. If blocking then stamped the blocker's
     * `left_at`, the leaver's next `GET /bonds/{id}` would *change* — and since
     * block is the only mutation an archived bond accepts, the only thing that
     * change could mean is "they blocked me". An oracle delivered to precisely
     * the person T-09 says must not be told, and one no amount of matching
     * status codes would have closed. Found by the Codex review bot on PR #39;
     * `DiscreetExitTest` now holds it.
     *
     * Leaving the blocker's membership unstamped costs nothing: FR-025 counts
     * memberships in *open* bonds, so no slot is held, and the record of the
     * block is the `blocks` row, which no response exposes. It also makes a
     * repeat block a true no-op — the returned object is equal, so nothing is
     * written and the `version` behind the `ETag` cannot count blocks either.
     *
     * A bond already in `PENDING_DELETION` is likewise untouched: B5's
     * cooling-off is running and the deletion job reads that status.
     */
    fun end(
        memberId: MemberId,
        now: Instant,
    ): Bond =
        if (!isOpen) {
            this
        } else {
            copy(
                status = BondStatus.ARCHIVED,
                archivedAt = now,
                members = members.map { if (it.id == memberId && it.isActive) it.copy(leftAt = now) else it },
            )
        }

    /**
     * A settings change (`PATCH /bonds/{bondId}`, ADR-0013 §8, ADR-0029).
     *
     * Every field of [settings] is optional and an absent one is left alone.
     * The constructor's `require`s run on the result, so a name that is blank
     * or too long cannot produce an object — the edge validates as well, and
     * this is the layer that cannot be bypassed.
     *
     * `check(isOpen)`: BR-9, and the same rule [leave] applies, because an
     * archived bond is a record rather than a thing with settings (ADR-0028).
     *
     * **The seats do not move when the type does.** FR-021 puts the limit on
     * the row, every v1 type seats two, and a type that ever seats a different
     * number needs a rule about the members already in the bond rather than an
     * arithmetic side effect here.
     */
    fun update(settings: BondSettings): Bond {
        check(isOpen) { "a bond that has ended cannot be changed" }
        return copy(
            name = settings.name ?: name,
            type = settings.type ?: type,
            revealTimeLocal = settings.revealTimeLocal.orKeep(revealTimeLocal),
            strictMode = settings.strictMode ?: strictMode,
        )
    }

    companion object {
        /** Chosen in the Phase 2 design (§5.2), not by FR-020. Flagged for Daniel. */
        const val MAX_NAME_LENGTH = 60

        const val MIN_MEMBERS = 2

        /**
         * FR-025's v1 abuse control. Counts bonds in `PENDING_MEMBER` or
         * `ACTIVE` that the user has not left — an archived one is a record,
         * not a place they can be written to.
         */
        const val MAX_OPEN_BONDS_PER_USER = 3

        /**
         * A new bond with its creator as `OWNER`, waiting for its second
         * member (doc 04 §4.3).
         *
         * It starts in `PENDING_MEMBER` rather than `ACTIVE`, and that is
         * load-bearing beyond bookkeeping: doc 04 §8.3a stops Bond-days being
         * created at all while a bond is waiting, so a creator who writes
         * before their partner joins (`02` J1 requires that they can) does not
         * accumulate solo days and lose a streak that never started.
         */
        fun create(
            id: BondId,
            ownerMemberId: MemberId,
            draft: BondDraft,
            now: Instant,
        ): Bond {
            val owner =
                Member.owner(
                    id = ownerMemberId,
                    bondId = id,
                    userId = draft.creator,
                    reminderTimezone = draft.reminderTimezone ?: draft.anchorTimezone,
                    now = now,
                )
            return Bond(
                id = id,
                type = draft.type,
                name = draft.name,
                anchorTimezone = draft.anchorTimezone,
                timezoneChangedAt = null,
                revealTimeLocal = draft.revealTimeLocal,
                strictMode = false,
                status = BondStatus.PENDING_MEMBER,
                maxMembers = draft.type.maxMembers,
                createdBy = draft.creator,
                createdAt = now,
                archivedAt = null,
                deletionRequestedAt = null,
                version = 0,
                members = listOf(owner),
            )
        }
    }
}
