package com.moyi.gratitude.domain

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * The identifiers this module mints for itself.
 *
 * [BondDayId] and [EntryId] are the only ids `gratitude` allocates. The bond
 * id an [Entry] and a [BondDay] both carry, and the member id an [Entry]'s
 * author is, belong to `bond` — they cross the module boundary as bare
 * `UUID`s exactly as `com.moyi.bond.api.BondMembership` already hands them
 * over (ADR-0026): wrapping an id this module does not own and can never
 * validate would just be a second name for a `UUID`.
 *
 * The two ids wrapped here are **different UUID versions on purpose** (doc
 * 06 §1). `com.moyi.bond.domain`'s own `Ids.kt` mints every id it owns as a
 * v7, because none of them is what this module's v4 is protecting:
 *
 * - [BondDayId] is a **v7** ([com.moyi.common.core.IdGenerator.timeOrdered]
 *   is the production port's name for this). A Bond-day is not a secret —
 *   nothing in `states.md` asks a caller to hide when one was opened, and no
 *   route puts a [BondDayId] somewhere a stranger could guess at it — so the
 *   creation time a v7 embeds costs nothing, and the index locality it buys
 *   `bond_days_bond_date_key` and `bond_days_feed_idx` (doc 07 §2, V12) is
 *   worth having.
 * - [EntryId] is a **v4** ([com.moyi.common.core.IdGenerator.opaque]). BR-8
 *   locks a submitted entry's content until reveal, and doc 06 §1 reserves
 *   v4 for exactly this: a v7 id would leak, in the id itself and
 *   independent of anything BR-1 gates, the one piece of metadata BR-8
 *   exists to suppress — when the entry was written. An [EntryId] never
 *   carries a timestamp to begin with, so there is nothing for that leak to
 *   find.
 */
@JvmInline
internal value class BondDayId(
    val value: UUID,
) {
    companion object {
        /**
         * A fresh [BondDayId], time-ordered from [now].
         *
         * [BondDay.open] and [BondDay.openSuspended] are pure — no Spring,
         * no [com.moyi.common.core.IdGenerator] to inject — and already
         * receive [now] as the caller's own clock reading, so this factory
         * needs nothing threaded through beyond it. The bit layout below
         * mirrors `com.moyi.common.core.SystemIdGenerator.timeOrdered`
         * (RFC 9562 §5.7) without its cross-call monotonic counter: the id
         * handed back here is a *candidate*. `BondDayStore.openOrGet`
         * (Task 6) inserts it under `bond_days_bond_date_key`'s `ON
         * CONFLICT (bond_id, date) DO NOTHING` — on a race the losing
         * candidate is simply never read back, so within-millisecond
         * ordering is not load-bearing here the way it is for `users.id`.
         */
        fun fresh(now: Instant): BondDayId = BondDayId(uuidV7(now))
    }
}

@JvmInline
internal value class EntryId(
    val value: UUID,
)

private val secureRandom = SecureRandom()

private const val VERSION_7 = 7L
private const val VERSION_SHIFT = 12
private const val TIMESTAMP_SHIFT = 16
private const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
private const val RAND_A_BOUND = 1 shl 12
private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL

/** 0b10 in bits 64..65 — the RFC 9562 variant, the same one UUID v4 uses. */
private const val VARIANT_RFC_9562 = Long.MIN_VALUE

/** RFC 9562 §5.7's layout, stamped from [now] rather than a wall clock. See [BondDayId.fresh]. */
private fun uuidV7(now: Instant): UUID {
    val mostSignificantBits =
        ((now.toEpochMilli() and TIMESTAMP_MASK) shl TIMESTAMP_SHIFT) or
            (VERSION_7 shl VERSION_SHIFT) or
            secureRandom.nextInt(RAND_A_BOUND).toLong()
    val leastSignificantBits = (secureRandom.nextLong() and RAND_B_MASK) or VARIANT_RFC_9562
    return UUID(mostSignificantBits, leastSignificantBits)
}
