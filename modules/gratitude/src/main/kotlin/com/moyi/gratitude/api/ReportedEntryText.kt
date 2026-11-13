package com.moyi.gratitude.api

import java.time.LocalDate
import java.util.UUID

/**
 * The one door to a reported entry's words, for the admin console (A5 spec §4–§8, FR-112).
 * Admin-only: `ArchitectureTest` lets no module but `admin` import this, and within `admin`
 * only `ContentAccess`.
 *
 * Both methods run only inside a transaction they join (`MANDATORY`): the console's, which
 * writes the read's `audit_log` row, so that neither the words nor the record of reading them
 * commits without the other.
 *
 * Whether a report's entry may be read is gratitude's domain's rule (`wordsReadable`), asked
 * here by both methods, so the page that offers the read and the read itself cannot differ.
 */
interface ReportedEntryText {
    /**
     * The entry [reportId] names, read for [actor] (`admin:<accountId>`, written to
     * `entry_report_log.actor`), unless the report is not open, its reason is not
     * [expectedReason] (the page's, as A2's stale check), or the rule refuses it.
     *
     * Locks, in this order: the report `FOR SHARE` (a decision waits for the read; a read
     * waits for a decision), then the entry `FOR SHARE` (an erasure waits for the read; a read
     * waits for an erasure, and reads the tombstone). **No bond lock**: a withdrawn author is
     * read from the bond's unlocked view, after the entry's lock, so that a read holding the
     * report never waits on a bond whose holder waits on the report (the reporter's own
     * account erasure takes the bond, then empties the report's detail).
     *
     * One `WORDS_READ` row, no note, for [ReportedEntryRead.Shown] and [ReportedEntryRead.Erased];
     * nothing for a refusal. A row that is not erased and holds no text breaks the domain's
     * invariant (`Entry`: only an erased entry has no text) and throws [IllegalStateException],
     * naming the entry and never a word, before anything is written.
     */
    fun read(
        reportId: UUID,
        expectedReason: String,
        actor: String,
    ): ReportedEntryRead

    /**
     * What the report's page asks before it offers the read: null when the rule allows it,
     * otherwise why not. An unknown report is [WordsRefusal.NOT_OPEN]. Reads the report without
     * a lock and writes nothing.
     */
    fun whyNot(reportId: UUID): WordsRefusal?
}

/** Why a report's entry may not be read: the rule's refusals, as [ReportedEntryText.whyNot] answers them. */
enum class WordsRefusal { NOT_OPEN, MEMBER_REPORT, IMAGE_REASON }

/**
 * What [ReportedEntryText.read] answers. [entryId] is the reported entry's, and null only for a
 * member report and for a report that does not exist.
 */
sealed interface ReportedEntryRead {
    val entryId: UUID?

    /** The entry's words and its bond-day's date. Logged `WORDS_READ`. */
    data class Shown(
        override val entryId: UUID,
        val date: LocalDate,
        val words: ReportedWords,
    ) : ReportedEntryRead

    /** Its author erased it, or withdrew from the bond (the outbox may not have erased the row yet). Logged `WORDS_READ`. */
    data class Erased(
        override val entryId: UUID,
    ) : ReportedEntryRead

    /** Decided meanwhile, or no such report. Nothing written. */
    data class NotOpen(
        override val entryId: UUID?,
    ) : ReportedEntryRead

    /** The report's reason is not the one the page showed. Nothing written. */
    data class Stale(
        override val entryId: UUID?,
    ) : ReportedEntryRead

    /** A member report, or an image reason ([imageReason]: reclassify first). [reason] is the report's. Nothing written. */
    data class NotAboutWords(
        override val entryId: UUID?,
        val reason: String,
        val imageReason: Boolean,
    ) : ReportedEntryRead
}

/**
 * An entry's words, as read for the console. **Never printed**: [toString] says only that it
 * is redacted. Not a data class, and equal only to itself, so that no comparison, hash or
 * generated method ever handles the words as a value.
 */
class ReportedWords(
    val value: String,
) {
    override fun toString(): String = "ReportedWords(redacted)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
