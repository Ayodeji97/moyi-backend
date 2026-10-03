package com.moyi.gratitude.domain

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
 * **Neither wrapper generates its own value.** [BondDay.open],
 * [BondDay.openSuspended] and [Entry.submit] all take their id as a
 * parameter, exactly as [com.moyi.bond.domain.Bond.create] does — the
 * calling service mints it from the injected
 * [com.moyi.common.core.IdGenerator] port and passes it in. That is
 * deliberate and not merely consistent: [com.moyi.common.core.SystemIdGenerator]
 * is the one place in this codebase that gets a v7's bit layout right,
 * counter included, and it is what every test that needs to assert *which*
 * id was issued substitutes with
 * [com.moyi.common.testing.DeterministicIdGenerator]. A pure aggregate that
 * minted its own id would have to reimplement that layout a second time,
 * worse — with no counter, so two ids minted in the same millisecond would
 * stop sorting by creation order, the one property [BondDayId] is a v7 for
 * in the first place — and untestably, since nothing outside the port can be
 * swapped for a fake.
 *
 * The two ids wrapped here are **different UUID versions on purpose** (doc
 * 06 §1). `com.moyi.bond.domain`'s own `Ids.kt` mints every id it owns as a
 * v7, because none of them is what this module's v4 is protecting:
 *
 * - [BondDayId] is a **v7** ([com.moyi.common.core.IdGenerator.timeOrdered]).
 *   A Bond-day is not a secret — nothing in `states.md` asks a caller to
 *   hide when one was opened, and no route puts a [BondDayId] somewhere a
 *   stranger could guess at it — so the creation time a v7 embeds costs
 *   nothing, and the index locality it buys `bond_days_bond_date_key` and
 *   `bond_days_feed_idx` (doc 07 §2, V12) is worth having.
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
)

@JvmInline
internal value class EntryId(
    val value: UUID,
)
