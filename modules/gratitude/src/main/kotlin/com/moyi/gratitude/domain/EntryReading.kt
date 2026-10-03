package com.moyi.gratitude.domain

import java.time.Instant
import java.util.UUID

/**
 * Who is asking to read an entry: a member, and the bond that membership is
 * *of*. A member id is only meaningful together with the bond it belongs to,
 * and BR-1's first clause is about exactly that pairing.
 *
 * **Only `service.asReader` constructs one**, from the `bond.api.BondMembership`
 * that `BondAccess` resolved — never from ids a request supplied. The type
 * cannot enforce that itself (`gratitude.domain` imports nothing from `bond`,
 * so the factory has to live a layer out), so `ArchitectureTest` does: a
 * second construction site in this module's main code fails the build.
 */
internal data class Reader(
    val memberId: UUID,
    val bondId: UUID,
)

/** What BR-1 lets a [Reader] have of one [Entry] — [Entry.canBeReadBy]'s answer. */
internal enum class Readability {
    /** The reader is not a member of the entry's bond. Nothing of the entry is theirs, not even that it exists. */
    NOT_A_MEMBER,

    /**
     * Deleted or withdrawn, for a reader who **could read it before it was**:
     * its author, or a partner it had been revealed to. The row without its
     * words — they already know when it was written; only the text is gone.
     */
    TOMBSTONE,

    /**
     * Deleted or withdrawn, for a partner it was **never revealed to**. That
     * reader was only ever entitled to BR-8's locked shape, and an erasure
     * does not entitle them to more: who wrote it and that it is gone,
     * nothing else — no id, no timestamps.
     */
    TOMBSTONE_UNSEEN,

    /** The reader's own entry, or one that has been revealed. */
    FULL,

    /** A partner's entry that has not been revealed: BR-8's author-and-status, and nothing else. */
    LOCKED,
}

/**
 * Everything of an entry a reader who can — or once could — read it may
 * have: [Readability.FULL] or [Readability.TOMBSTONE]. [text] is `null`
 * exactly on the tombstone, and [status] is then [EntryStatus.DELETED]
 * whichever mark the row's erasure had reached.
 *
 * Only [EntryReading] builds one, and only for those two answers. A `data
 * class` for its value semantics; its `toString` prints [EntryText]'s own
 * redaction, never the words.
 */
internal data class DisclosedEntry(
    val id: EntryId,
    val bondId: UUID,
    val bondDayId: BondDayId,
    val authorMemberId: UUID,
    val createdAt: Instant,
    val intendedAt: Instant,
    val status: EntryStatus,
    val text: EntryText?,
)

/**
 * An [Entry] **after** BR-1 has been asked about it for one [Reader] — the
 * only form in which an entry leaves the service layer.
 *
 * The constructor is private and [Entry.readBy] is the one way to get one, so
 * holding an `EntryReading` *is* the evidence the gate ran. The entry itself
 * is not exposed, and what is exposed is decided by [readability], here, once:
 *
 * - [disclosed] — the entry's id, timestamps, status and (unless a
 *   tombstone) words — is non-null **only** for [Readability.FULL] and
 *   [Readability.TOMBSTONE]. A [Readability.LOCKED] or
 *   [Readability.TOMBSTONE_UNSEEN] reading has no accessor that returns any
 *   of them, so a renderer written later cannot leak what BR-8 withholds by
 *   reaching for a field: there is nothing to reach.
 * - [authorMemberId] is the one fact BR-8 shares, and is `null` only for a
 *   non-member.
 */
internal class EntryReading private constructor(
    private val entry: Entry,
    val readability: Readability,
) {
    val authorMemberId: UUID? get() = entry.authorMemberId.takeUnless { readability == Readability.NOT_A_MEMBER }

    val disclosed: DisclosedEntry?
        get() =
            when (readability) {
                Readability.FULL -> disclose(entry.status, entry.text)
                Readability.TOMBSTONE -> disclose(EntryStatus.DELETED, null)
                Readability.TOMBSTONE_UNSEEN, Readability.LOCKED, Readability.NOT_A_MEMBER -> null
            }

    private fun disclose(
        status: EntryStatus,
        text: EntryText?,
    ) = DisclosedEntry(
        id = entry.id,
        bondId = entry.bondId,
        bondDayId = entry.bondDayId,
        authorMemberId = entry.authorMemberId,
        createdAt = entry.createdAt,
        intendedAt = entry.intendedAt,
        status = status,
        text = text,
    )

    /** Never the entry, nor anything of it: only what the gate answered. */
    override fun toString(): String = "EntryReading(readability=$readability)"

    companion object {
        /** [Entry.readBy]'s seat — `internal` only so [Entry] can reach it; nothing else calls it. */
        internal fun of(
            entry: Entry,
            reader: Reader,
        ): EntryReading = EntryReading(entry, entry.canBeReadBy(reader))
    }
}
