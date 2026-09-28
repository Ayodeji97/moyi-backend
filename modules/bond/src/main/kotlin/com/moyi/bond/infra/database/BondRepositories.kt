package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.ProposalKind
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

    /**
     * Writes and flushes.
     *
     * The flush is not for the optimistic check — that cannot fire on this path
     * (see [BondStore.update]). It is for the **`ETag`**: `@Version` is
     * incremented at flush, and `EntityManager.find` answers from the
     * persistence context without one, so a service that writes and then
     * re-reads sees the *old* version and hands the client an `ETag` that its
     * next `If-Match` would be refused with. Found by `BondSettingsEndpointTest`
     * — expected `"1"`, got `"0"`.
     */
    fun saveAndFlush(bond: BondEntity): BondEntity

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
 * Proposals (FR-027, FR-028). Every finder is scoped to a bond — there is no
 * "all proposals", for the reason doc 05 §5.5 gives about every other table
 * here.
 *
 * Nothing in this interface loads a proposal by id alone, either: the two state
 * changes take the id *and* the predicate that makes the change legal, so a
 * caller cannot read one, decide, and write over somebody else's decision.
 */
internal interface BondProposalRepository : Repository<BondProposalEntity, UUID> {
    fun save(proposal: BondProposalEntity): BondProposalEntity

    /** The open, unlapsed proposal of one kind, if there is one. */
    @Query(
        """
        SELECT p FROM BondProposalEntity p
         WHERE p.bondId = :bondId
           AND p.kind = :kind
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt > :now
        """,
    )
    fun findLive(
        bondId: UUID,
        kind: ProposalKind,
        now: Instant,
    ): BondProposalEntity?

    /**
     * Every live proposal of the listed bonds, for the response assembler.
     *
     * Batched over a list rather than called per bond: `GET /bonds` returns up
     * to three and this is the query that would otherwise be the N+1 — the same
     * reason [findAllLiveByBondIdIn] exists for invites.
     */
    @Query(
        """
        SELECT p FROM BondProposalEntity p
         WHERE p.bondId IN :bondIds
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt > :now
        """,
    )
    fun findAllLiveOf(
        bondIds: Collection<UUID>,
        now: Instant,
    ): List<BondProposalEntity>

    /**
     * Closes a **lapsed** proposal of this kind, so V10's partial unique index
     * stops holding its slot (ADR-0030).
     *
     * `cancelled_at` rather than a third ending, because from the system's point
     * of view a lapsed proposal is one nobody will ever confirm and this is the
     * moment it stopped being considered. Nothing runs on a schedule — a row is
     * closed only when a new proposal needs the slot, which is what spec §6.4's
     * "never reaped" means in practice.
     *
     * @return how many were closed: zero or one, and a number rather than a
     *   boolean because two would mean the unique index was not doing its job.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.cancelledAt = :now
         WHERE p.bondId = :bondId
           AND p.kind = :kind
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt <= :now
        """,
    )
    fun closeLapsed(
        bondId: UUID,
        kind: ProposalKind,
        now: Instant,
    ): Int

    /**
     * Confirms a proposal, if it is still live — the compare-and-set that makes
     * two simultaneous confirmations produce one applied change. The same shape
     * as `BondInviteRepository.consume`, and for the same reason: a
     * read-then-check-then-write in Kotlin has no such guarantee, and what it
     * permits here is a zone change applied twice, the second time from a
     * proposal that was already answered.
     *
     * @return `1` if this call confirmed it; `0` if somebody else did, or it was
     *   cancelled or lapsed in the meantime.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.confirmedAt = :now,
               p.confirmedByMemberId = :memberId
         WHERE p.id = :id
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt > :now
        """,
    )
    fun confirm(
        id: UUID,
        memberId: UUID,
        now: Instant,
    ): Int

    /**
     * Cancels one open proposal, lapsed or not.
     *
     * Deliberately **no** `expires_at` predicate: cancelling something that has
     * quietly lapsed is a no-op the caller does not need told about, and
     * `states.md` §8's pending screen may well still be showing it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.cancelledAt = :now
         WHERE p.id = :id
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
        """,
    )
    fun cancel(
        id: UUID,
        now: Instant,
    ): Int

    /**
     * Cancels every open proposal of a bond — what ADR-0028 requires when a bond
     * ends, and the obligation that ADR recorded because this table did not yet
     * exist. A confirmation arriving on an archived bond would otherwise try to
     * move the anchor zone of a bond that has ended.
     *
     * @return how many were cancelled, for the test and the log line.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.cancelledAt = :now
         WHERE p.bondId = :bondId
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
        """,
    )
    fun cancelLiveOf(
        bondId: UUID,
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
     * Records a block, or does nothing if that exact one is already recorded.
     *
     * `ON CONFLICT DO NOTHING` rather than a read followed by an insert,
     * because FR-029 permits blocking a bond that has already ended and
     * therefore permits blocking twice: the second call has to be the same
     * `204` as the first, and a check-then-insert would turn two concurrent
     * ones into a constraint violation the caller sees as a 500.
     *
     * Native, because JPQL has no `INSERT`. `flushAutomatically` so anything
     * pending in this transaction is written before the statement runs.
     *
     * @return `1` if this call wrote the row, `0` if it was already there.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO blocks (id, blocker_user_id, blocked_user_id, bond_id, created_at)
            VALUES (:id, :blockerUserId, :blockedUserId, :bondId, :createdAt)
            ON CONFLICT (blocker_user_id, blocked_user_id, bond_id) DO NOTHING
            """,
    )
    fun insertIfAbsent(
        id: UUID,
        blockerUserId: UUID,
        blockedUserId: UUID,
        bondId: UUID,
        createdAt: Instant,
    ): Int

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
