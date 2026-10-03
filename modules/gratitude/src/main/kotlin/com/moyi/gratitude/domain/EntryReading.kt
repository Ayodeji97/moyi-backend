package com.moyi.gratitude.domain

import java.time.Instant
import java.util.UUID

/**
 * Who is asking to read an entry: a member, and the bond that membership is
 * *of*. Built from a `bond.api.BondMembership` the caller already resolved
 * (`service.asReader`), never from ids a request supplied — a member id is
 * only meaningful together with the bond it belongs to, and BR-1's first
 * clause is about exactly that pairing.
 */
internal data class Reader(
    val memberId: UUID,
    val bondId: UUID,
)

/** What BR-1 lets a [Reader] have of one [Entry] — [Entry.canBeReadBy]'s answer. */
internal enum class Readability {
    /** The reader is not a member of the entry's bond. Nothing of the entry is theirs, not even that it exists. */
    NOT_A_MEMBER,

    /** Deleted or withdrawn: the row, without its words, for everyone — its author included. */
    TOMBSTONE,

    /** The reader's own entry, or one that has been revealed. */
    FULL,

    /** A partner's entry that has not been revealed: BR-8's author-and-status, and nothing else. */
    LOCKED,
}

/**
 * An [Entry] **after** BR-1 has been asked about it for one [Reader] — the
 * only form in which an entry leaves the service layer.
 *
 * The constructor is private and [Entry.readBy] is the one way to get one, so
 * holding an `EntryReading` *is* the evidence the gate ran: a renderer that
 * takes this type cannot be handed an entry nobody asked BR-1 about, and
 * [text] answers `null` for anything short of [Readability.FULL] whatever the
 * row still holds. The entry itself is not exposed.
 *
 * [status] is [EntryStatus.DELETED] on every tombstone, whichever of
 * `deleted_at` and `status` the row's erasure had reached — one tombstone
 * shape, not one per way of being erased.
 *
 * What a renderer does with a [Readability.LOCKED] reading is BR-8's business,
 * not this type's: `web.LockedEntryResponse` has nowhere to put anything but
 * [authorMemberId].
 */
internal class EntryReading private constructor(
    private val entry: Entry,
    val readability: Readability,
) {
    val id: EntryId get() = entry.id
    val bondId: UUID get() = entry.bondId
    val bondDayId: BondDayId get() = entry.bondDayId
    val authorMemberId: UUID get() = entry.authorMemberId
    val createdAt: Instant get() = entry.createdAt
    val intendedAt: Instant get() = entry.intendedAt
    val status: EntryStatus get() = if (readability == Readability.TOMBSTONE) EntryStatus.DELETED else entry.status
    val text: EntryText? get() = entry.text.takeIf { readability == Readability.FULL }

    /** Never the entry: its `toString` is redacted today, and this must not depend on that staying true. */
    override fun toString(): String = "EntryReading(id=$id, readability=$readability)"

    companion object {
        /** [Entry.readBy]'s seat — `internal` only so [Entry] can reach it; nothing else calls it. */
        internal fun of(
            entry: Entry,
            reader: Reader,
        ): EntryReading = EntryReading(entry, entry.canBeReadBy(reader))
    }
}
