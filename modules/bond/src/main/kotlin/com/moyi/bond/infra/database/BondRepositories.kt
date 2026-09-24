package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondStatus
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

/**
 * Bonds.
 *
 * On Spring Data's bare [Repository] marker rather than `JpaRepository`, for
 * the reason `CredentialsRepository` gives: **what a repository cannot do is
 * part of its design.** `JpaRepository` would inherit `findAll()`,
 * `getReferenceById()` and a dozen more, each of them a supported way to pull
 * every bond in the system into memory. Declaring the handful of methods this
 * module actually makes is what keeps doc 05 §5.5's third layer true — there
 * is no query here that returns bond-scoped data without a bond id or a user
 * id in its predicate, and [BondStore] is the only caller of any of them.
 */
internal interface BondRepository : Repository<BondEntity, UUID> {
    fun save(bond: BondEntity): BondEntity

    fun findById(id: UUID): BondEntity?

    fun findAllByIdIn(ids: Collection<UUID>): List<BondEntity>

    /**
     * Serialises "count my open bonds, then create one" for the rest of the
     * current transaction, and is the reason FR-025's limit cannot be raced.
     *
     * Without it the count and the insert are two statements over a READ
     * COMMITTED snapshot: two concurrent creates both see two bonds, both
     * decide there is room, and the user ends with four. The same shape as the
     * refresh-token race Codex found on PR #32, and the same answer —
     * a transaction-scoped advisory lock keyed on the user, released
     * automatically at commit or rollback.
     *
     * Namespace `2` in the two-key form; identity's sessions lock is `1`, so
     * the two cannot collide by accident.
     */
    @Query(nativeQuery = true, value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(2, hashtext(CAST(:userId AS text)))) AS held")
    fun lockBondsOf(userId: UUID): Int

    /**
     * Holds the bond's row for the rest of the transaction, so that "is there
     * a seat" and "take it" cannot interleave with another accept.
     *
     * A row lock rather than an advisory one because the contended thing *is*
     * a row, and `FOR UPDATE` releases at commit with no key to agree on. It
     * is the outer guard; `BondInviteRepository.consume` is the inner
     * compare-and-set, and either alone would do — both are here because the
     * lock serialises the whole decision (the limit check, the block check)
     * while the CAS is what remains true if a future caller forgets to take
     * it.
     */
    @Query(nativeQuery = true, value = "SELECT 1 FROM bonds WHERE id = :id FOR UPDATE")
    fun lockRow(id: UUID): Int?
}

/** Memberships. Every finder is scoped by bond or by user — there is no "all members" query. */
internal interface BondMemberRepository : Repository<BondMemberEntity, UUID> {
    fun saveAll(members: Iterable<BondMemberEntity>): List<BondMemberEntity>

    fun findAllByBondId(bondId: UUID): List<BondMemberEntity>

    fun findAllByBondIdIn(bondIds: Collection<UUID>): List<BondMemberEntity>

    fun findAllByUserId(userId: UUID): List<BondMemberEntity>

    /**
     * FR-025's count: memberships this user has not left, in bonds whose
     * status is one of [statuses].
     *
     * Counted in the database rather than by loading the bonds and filtering
     * in Kotlin, because the answer is a number and the rows are nobody's
     * business here.
     */
    @Query(
        """
        SELECT COUNT(m) FROM BondMemberEntity m, BondEntity b
         WHERE b.id = m.bondId
           AND m.userId = :userId
           AND m.leftAt IS NULL
           AND b.status IN :statuses
        """,
    )
    fun countByUserIdAndBondStatusIn(
        userId: UUID,
        statuses: Collection<BondStatus>,
    ): Long
}

/** Invites. */
internal interface BondInviteRepository : Repository<BondInviteEntity, UUID> {
    fun save(invite: BondInviteEntity): BondInviteEntity

    /**
     * The live invite of each listed bond — at most one per bond once slice B2
     * enforces "a new invite revokes the outstanding one" (doc 06 §3.3).
     *
     * Batched over a list rather than called per bond: `GET /bonds` returns up
     * to three bonds and this is the query that would otherwise be the N+1.
     */
    @Query(
        """
        SELECT i FROM BondInviteEntity i
         WHERE i.bondId IN :bondIds
           AND i.usedAt IS NULL
           AND i.revokedAt IS NULL
           AND i.expiresAt > :now
        """,
    )
    fun findAllLiveByBondIdIn(
        bondIds: Collection<UUID>,
        now: Instant,
    ): List<BondInviteEntity>

    /**
     * The code a caller presented, if it is still usable. The three predicates
     * are the three ways a code dies, and they are **here rather than in
     * Kotlin** so that a dead code and an invented one are one answer to the
     * caller without the service having to remember to collapse them
     * (FR-024).
     */
    @Query(
        """
        SELECT i FROM BondInviteEntity i
         WHERE i.code = :code
           AND i.usedAt IS NULL
           AND i.revokedAt IS NULL
           AND i.expiresAt > :now
        """,
    )
    fun findLiveByCode(
        code: String,
        now: Instant,
    ): BondInviteEntity?

    /**
     * Revokes whatever live invite a bond has, so the new one is the only one
     * (doc 06 §3.3, `states.md` §2 "Replaced").
     *
     * Spent invites are deliberately untouched: `used_at` and `used_by_user_id`
     * are the record of who joined and when, and revoking them would overwrite
     * history with bookkeeping.
     *
     * @return how many were revoked — zero or one, and a number rather than a
     *   boolean because a second live invite would be a bug worth seeing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondInviteEntity i
           SET i.revokedAt = :now
         WHERE i.bondId = :bondId
           AND i.usedAt IS NULL
           AND i.revokedAt IS NULL
           AND i.expiresAt > :now
        """,
    )
    fun revokeLiveOf(
        bondId: UUID,
        now: Instant,
    ): Int

    /**
     * Revokes one named invite of one bond (`DELETE /bonds/{id}/invites/{id}`).
     * The `bondId` predicate is the authorisation: another bond's invite id
     * matches no row, and no row is the 404 that does not confirm it exists.
     *
     * @return `1` if this call revoked it; `0` if it was already dead, belongs
     *   to another bond, or never existed — one answer for all three.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondInviteEntity i
           SET i.revokedAt = :now
         WHERE i.id = :id
           AND i.bondId = :bondId
           AND i.usedAt IS NULL
           AND i.revokedAt IS NULL
           AND i.expiresAt > :now
        """,
    )
    fun revokeOf(
        bondId: UUID,
        id: UUID,
        now: Instant,
    ): Int

    /**
     * Spends an invite, if it is still live — a compare-and-set written in
     * SQL, and the single most important statement in this slice.
     *
     * Two people presenting the same code at the same moment both read it as
     * live. Only one of them gets `1` back here, because the database
     * serialises the two `UPDATE`s and the second finds `used_at IS NULL` no
     * longer true. A read-then-check-then-write in Kotlin has no such
     * guarantee, and the failure it permits is two members joining a two-seat
     * bond that already had its creator — a third person in a private
     * conversation, which is the worst thing this module can do.
     *
     * The same shape as `VerificationTokenRepository.consume`, for the same
     * reason and with the same `clearAutomatically`: a bulk JPQL update
     * bypasses the persistence context, so an entity loaded earlier in this
     * transaction would still claim to be unspent.
     *
     * @return `1` if this call spent it; `0` if somebody else did, or it
     *   expired or was revoked in the meantime.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondInviteEntity i
           SET i.usedAt = :now,
               i.usedByUserId = :userId
         WHERE i.id = :id
           AND i.usedAt IS NULL
           AND i.revokedAt IS NULL
           AND i.expiresAt > :now
        """,
    )
    fun consume(
        id: UUID,
        userId: UUID,
        now: Instant,
    ): Int
}

/**
 * Blocks (FR-029). Written by slice B3; B2 only asks the one question.
 *
 * No `findAll()`, and no finder by blocker alone: "who has this person
 * blocked" is a list nothing in the application needs and a support tool
 * would have to justify (T-09, T-10).
 */
internal interface BlockRepository : Repository<BlockEntity, UUID> {
    fun save(block: BlockEntity): BlockEntity

    /**
     * Is there a block **either way** between [userId] and any of [others]?
     *
     * Both directions in one query, because FR-029 says a block prevents any
     * future invitation *between* two accounts — and the person holding the
     * code may be either of them. Checking one direction would let the blocked
     * party invite the blocker back, which is precisely the contact the
     * requirement forbids.
     */
    @Query(
        """
        SELECT COUNT(b) > 0 FROM BlockEntity b
         WHERE (b.blockerUserId = :userId AND b.blockedUserId IN :others)
            OR (b.blockedUserId = :userId AND b.blockerUserId IN :others)
        """,
    )
    fun existsBetween(
        userId: UUID,
        others: Collection<UUID>,
    ): Boolean
}
