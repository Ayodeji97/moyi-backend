package com.moyi.gratitude.domain

import java.util.UUID

/**
 * Why a member reports their partner's entry (FR-100, ADR-0053 decision 3).
 * A small closed set: doc 26 §6 proposed four values that mixed what was
 * reported with why (OQ-26b, open), and the owner's ruling Q16 names the
 * cases a person must be able to act on. What is reported is the entry, with
 * its photo and voice note; this says why.
 *
 * The set is written once, here. The edge validator asks [named], the
 * contract's enums are generated from it, and V39's `CHECK` holds the same
 * names. Adding or renaming one is a migration and a breaking change for a
 * generated client.
 */
internal enum class ReportReason(
    /** Reviewed first, and never viewed by the operator: the takedown runbook's §2 (Q16). Never blocked by the route. */
    val priority: Boolean = false,
) {
    /** Intimate imagery of the reporter, or of anyone, shared without consent (Q16: no classifier can judge consent). */
    NON_CONSENSUAL,

    /** Sexual content involving a minor. Held back like any other, and marked for priority review; a person decides. */
    MINOR_SEXUAL_CONTENT(priority = true),

    /** Violence, gore or self-harm. */
    VIOLENCE_OR_SELF_HARM,

    /** How the partner is treating the reporter (doc 26 §6: T-09, coercion, is not "content" and needs somewhere to go). */
    HARASSMENT,

    /** Anything else; the detail says what. */
    OTHER,
    ;

    companion object {
        /** Exactly the name, as `ReactionType.named`: `other` is not [OTHER], and nothing is trimmed. */
        fun named(name: String): ReportReason? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The reporter's own words to the person who reviews the report: optional, at
 * most [MAX_CHARACTERS] code points, not blank, no NUL (Postgres refuses one
 * in text). Never a word of the entry: the entry is named by its id.
 *
 * **Never printed.** It is what a frightened person wrote about the person
 * they share a journal with; it goes to the operator through the database
 * function that logs the look, and nowhere else (decision 7). [toString]
 * says only how long it is.
 */
internal class ReportDetail private constructor(
    val value: String,
) {
    override fun toString(): String = "ReportDetail(${value.codePointCount(0, value.length)} code points)"

    override fun equals(other: Any?): Boolean = other is ReportDetail && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** V39's `entry_reports_detail_check` counts the same unit: `char_length` is code points. */
        const val MAX_CHARACTERS = 500

        /**
         * The detail, or the reason it is not one, as an [IllegalArgumentException]
         * whose message the edge reports: never the value.
         */
        fun of(raw: String): ReportDetail {
            require('\u0000' !in raw) { "must not contain U+0000" }
            require(!raw.hasUnpairedSurrogate()) { "must be valid Unicode" }
            require(raw.isNotBlank()) { "must not be blank; leave it out instead" }
            require(raw.codePointCount(0, raw.length) <= MAX_CHARACTERS) { "must be at most $MAX_CHARACTERS characters" }
            return ReportDetail(raw)
        }

        private fun String.hasUnpairedSurrogate(): Boolean =
            indices.any { i ->
                val c = this[i]
                (c.isHighSurrogate() && (i + 1 >= length || !this[i + 1].isLowSurrogate())) ||
                    (c.isLowSurrogate() && (i == 0 || !this[i - 1].isHighSurrogate()))
            }
    }
}

/**
 * A member's report of their partner's entry, as the reporter is told of it:
 * its id, the entry and the reason. Nothing of the detail (it is the
 * operator's to read, through `entry_report_read`) and nothing of what was
 * decided (decision 6 of ADR-0053: the reporter is told a person will read
 * it, not what they did).
 */
internal data class EntryReport(
    val id: UUID,
    val entryId: EntryId,
    val bondId: UUID,
    val reporterMemberId: UUID,
    val reason: ReportReason,
)

/**
 * FR-100: may [reader] report this entry to a person (ADR-0053)? **The
 * read gate's answer for that reader, and whether they wrote it**, as
 * [Entry.canBeReactedToBy]'s is, with one difference: a tombstone can still be
 * reported. What the reader was shown before it was erased may be what
 * they are reporting (doc 26 §4: the report starts from the archive's day
 * view, which carries the id of an entry read in full or as a tombstone).
 *
 * - Anything the reader was never shown: no, and not in a way that says
 *   the entry exists. Asked first, so that "it is your own" is only ever
 *   said to a member of the entry's bond.
 * - The reader's own entry, in any state: no. A report is about what the
 *   other person shared.
 * - The partner's, [Readability.FULL]: yes, [Reporting.IN_FULL].
 * - The partner's, [Readability.TOMBSTONE]: yes, [Reporting.ERASED]; its
 *   media is already gone.
 *
 * Nothing about whether the bond has ended: a report is never contact
 * (doc 26 §2.2: the reported member is never told), and someone who has
 * blocked their partner may need it most.
 *
 * An extension beside the report's other rules rather than a member of
 * [Entry], which holds the gate it asks.
 */
internal fun Entry.canBeReportedBy(reader: Reader): Reporting =
    when (canBeReadBy(reader)) {
        Readability.LOCKED, Readability.TOMBSTONE_UNSEEN, Readability.NOT_A_MEMBER -> Reporting.NEVER_SHOWN
        Readability.FULL -> if (authorMemberId == reader.memberId) Reporting.OWN_ENTRY else Reporting.IN_FULL
        Readability.TOMBSTONE -> if (authorMemberId == reader.memberId) Reporting.OWN_ENTRY else Reporting.ERASED
    }
