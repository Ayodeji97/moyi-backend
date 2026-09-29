package com.moyi.bond.api

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
 * Nine fields, not seven arguments to reorder by accident: every one is
 * named at every call site (`BondAccessAdapter`'s only constructor), and the
 * shape is the five bond facts above plus the caller's own identifiers —
 * splitting it into a nested value would just move the count, not reduce it.
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
) {
    /** Ids only — a bond's name is the couple's words (doc 18 §9). */
    override fun toString(): String = "BondMembership(bondId=$bondId, memberId=$memberId)"
}
