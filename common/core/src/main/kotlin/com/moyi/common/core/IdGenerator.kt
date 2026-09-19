package com.moyi.common.core

import java.util.UUID

/**
 * Supplies the primary keys this system assigns **in application code**,
 * not in the database.
 *
 * Doc 06 §1 makes two different choices for two different reasons, and the
 * method names here are the reasons rather than the RFC 9562 version
 * numbers — a caller should have to think about which property it needs:
 *
 * - [timeOrdered] — a UUID v7, whose leading 48 bits are a millisecond
 *   timestamp. Sorting by id sorts by creation, so inserts land at the
 *   right-hand edge of the B-tree instead of scattering across it. Used
 *   for `users` and `bonds`.
 * - [opaque] — a UUID v4, uniformly random and carrying no metadata. Used
 *   for entries and media, where the creation time is exactly what BR-8
 *   suppresses on a locked entry: a v7 id would leak, in the id itself,
 *   the thing the domain is hiding.
 *
 * This is a port in the Strategy sense, and it exists for the same reason
 * [java.time.Clock] is injected rather than called statically (doc 18 §3):
 * a test that needs to know which ids were created can supply its own
 * implementation instead of matching on "some random UUID".
 */
interface IdGenerator {
    /** A time-ordered id (UUID v7). Use where index locality matters and the creation time is not a secret. */
    fun timeOrdered(): UUID

    /** An opaque, uniformly random id (UUID v4). Use where the id must reveal nothing, including when it was made. */
    fun opaque(): UUID
}
