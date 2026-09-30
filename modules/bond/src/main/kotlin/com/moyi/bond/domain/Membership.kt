package com.moyi.bond.domain

/**
 * Proof that the caller is a member of a bond — the value every bond-scoped
 * service function takes **instead of** a bare [BondId] (doc 05 §5.5 layer 2,
 * doc 09 §4).
 *
 * **Only `BondAccessGuard` constructs one, and that is the whole design.** A
 * service that needs a `Membership` cannot be reached by a controller that
 * skipped the guard, because nothing else can produce the argument: the
 * authorisation check is not a step someone can forget, it is a value they
 * must be holding. Doc 12 §3.6 asks for a rule that "every controller method
 * taking a bondId passes through BondAccessGuard"; this is that rule made
 * structural, with two Konsist tests in `app` holding the halves a type
 * cannot — that nothing else calls the constructor, and that no service
 * function takes a `BondId` without one.
 *
 * The pattern has a name worth knowing: an object capability. The reference
 * *is* the permission, so authority travels by being passed rather than by
 * being looked up, and there is no ambient "current user" a deep call can
 * consult and get wrong.
 *
 * [left] is true for someone who has left an archived bond. They may read —
 * `states.md` §9 keeps the archive open to both members — so the flag is
 * carried rather than the membership being withheld.
 *
 * **What refuses them is `isOpen`, in each write path, and not this flag.**
 * The earlier version of this sentence said "every write path refuses them",
 * which was true and was not a description of any mechanism: leaving archives
 * the bond, so `check(isOpen)` turns them away without anything reading
 * [left]. The flag had **no readers at all** until `RequestDeletion.cancel`,
 * which drops the `isOpen` check on purpose — a cooling-off that consulted it
 * could never be cancelled — and therefore has to consult this instead
 * (ADR-0030 §4a-i). Found by the second review of PR #41, after a member who
 * had walked out of a bond revoked the deletion both members had agreed to.
 *
 * So: **a write path that does not check `isOpen` must check [left] itself.**
 * The sentence this replaces was the reason nobody did.
 */
internal data class Membership(
    val bondId: BondId,
    val memberId: MemberId,
    val userId: UserId,
    val role: MemberRole,
    val left: Boolean,
)
