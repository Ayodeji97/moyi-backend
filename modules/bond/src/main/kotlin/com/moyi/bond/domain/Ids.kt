package com.moyi.bond.domain

import java.util.UUID

/**
 * The identifiers this module deals in, each wrapped so the compiler can tell
 * them apart.
 *
 * All of them are `UUID` underneath, which is exactly the problem: an
 * argument-order mistake between two UUIDs is invisible at every layer until
 * it reads the wrong row, and in a bond-scoped system reading the wrong row
 * is T-02. `@JvmInline value class`, so the wrapping costs nothing at
 * runtime — the JVM sees a bare UUID.
 *
 * All four are UUID v7 ([com.moyi.common.core.IdGenerator.timeOrdered]): none
 * of them is a secret, so the creation time a v7 embeds is not something to
 * hide, and the index locality is worth having. Doc 06 §1 reserves v4 for
 * entry and media ids, where BR-8 suppresses exactly that metadata.
 */
@JvmInline
internal value class BondId(
    val value: UUID,
)

@JvmInline
internal value class MemberId(
    val value: UUID,
)

@JvmInline
internal value class InviteId(
    val value: UUID,
)

/**
 * A user, as this module sees one: an id it never dereferences itself.
 *
 * Identity has a `UserId` of its own and it is `internal` to that module —
 * correctly, because a type is not a contract. What crosses the boundary is
 * a bare `UUID` through `identity.api.UserDirectory`; this wrapper is how the
 * bond module keeps it straight on its own side.
 */
@JvmInline
internal value class UserId(
    val value: UUID,
)
