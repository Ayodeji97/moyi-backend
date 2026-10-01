package com.moyi.bond.infra.database

import com.moyi.bond.domain.AnchorInterval
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.Handoff
import com.moyi.common.core.IdGenerator
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Anchor intervals. One finder for the whole timeline and one for the open
 * row — there is no "by id" lookup, because nothing outside [AnchorIntervalStore]
 * addresses a single interval on its own (the `BondRepository` precedent: what
 * a repository cannot do is part of its design).
 */
internal interface AnchorIntervalRepository : Repository<AnchorIntervalEntity, UUID> {
    fun save(interval: AnchorIntervalEntity): AnchorIntervalEntity

    /**
     * Writes and flushes.
     *
     * [AnchorIntervalStore.scheduleHandoff] needs this, not plain `save`: closing
     * the old interval and opening the new one are an `UPDATE` and an `INSERT` in
     * the same flush, and Hibernate's action queue always runs every insert
     * before every update regardless of call order. Without flushing the close
     * first, the new row's `effective_to IS NULL` reaches
     * `bond_anchor_intervals_open_key` while the old row is still open too —
     * `ERROR: duplicate key value violates unique constraint` on every
     * confirmation, found the moment this slice's own test ran against Postgres.
     */
    fun saveAndFlush(interval: AnchorIntervalEntity): AnchorIntervalEntity

    /** The whole timeline, oldest first — what [AnchorTimeline]'s contiguity check expects. */
    fun findAllByBondIdOrderByEffectiveFromAsc(bondId: UUID): List<AnchorIntervalEntity>

    /** The one row with no `effective_to` — null only if the bond has none yet, which never happens past creation. */
    fun findByBondIdAndEffectiveToIsNull(bondId: UUID): AnchorIntervalEntity?
}

/**
 * The bond's effective-zone history. **Every write here assumes the bond's
 * row lock is already held** — `BondStore.lockBond` — because the open
 * interval is read, closed and replaced as one decision, and two confirmations
 * racing would otherwise both close the same interval and leave two open ones.
 * The partial unique index `bond_anchor_intervals_open_key` is the backstop
 * that turns that race into a constraint violation rather than a silent
 * ambiguity; the lock is what keeps it from ever being reached.
 *
 * Not transactional itself, for the reason `ProposalStore`'s own KDoc gives:
 * the boundary is the caller's, because the interesting sequence — read the
 * timeline, decide the handoff, close one row and open the next — has to be
 * one transaction or none of it.
 */
@Component
internal class AnchorIntervalStore(
    private val intervals: AnchorIntervalRepository,
    private val ids: IdGenerator,
) {
    fun timelineOf(bondId: BondId): AnchorTimeline =
        AnchorTimeline(
            intervals.findAllByBondIdOrderByEffectiveFromAsc(bondId.value).map {
                AnchorInterval(ZoneId.of(it.zone), it.effectiveFrom, it.effectiveTo, it.firstLabel)
            },
        )

    /** The first interval, written when the bond is created. */
    fun seed(
        bondId: BondId,
        zone: ZoneId,
        at: Instant,
    ) {
        intervals.save(
            AnchorIntervalEntity(
                id = ids.timeOrdered(),
                bondId = bondId.value,
                zone = zone.id,
                firstLabel = at.atZone(zone).toLocalDate(),
                effectiveFrom = at,
                effectiveTo = null,
                createdAt = at,
            ),
        )
    }

    /**
     * Close the open interval at the handoff and open the next one there.
     * Both writes, or neither: the caller's transaction is what makes that
     * true, and the caller holds the bond lock.
     */
    fun scheduleHandoff(
        bondId: BondId,
        handoff: Handoff,
        newZone: ZoneId,
        now: Instant,
    ) {
        val open =
            intervals.findByBondIdAndEffectiveToIsNull(bondId.value)
                ?: error("a bond always has one open anchor interval")
        open.effectiveTo = handoff.at
        // Flushed before the insert below reaches the database — see
        // `saveAndFlush`'s own note on why plain `save` is not enough here.
        intervals.saveAndFlush(open)
        intervals.save(
            AnchorIntervalEntity(
                id = ids.timeOrdered(),
                bondId = bondId.value,
                zone = newZone.id,
                firstLabel = handoff.firstLabel,
                effectiveFrom = handoff.at,
                effectiveTo = null,
                createdAt = now,
            ),
        )
    }
}
