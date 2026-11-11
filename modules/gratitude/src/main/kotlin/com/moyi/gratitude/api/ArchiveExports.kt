package com.moyi.gratitude.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A member's archive of one bond, **exactly as the app shows it to them**, a page at a time, for
 * the data export (FR-009, FR-094, ADR-0059). `export` knows when and where; this module knows what
 * a member may read.
 *
 * **It is `GET /bonds/{bondId}/days`, not a second reading of the rows.** Every day comes through
 * the same gate the archive route uses (BR-1, ADR-0036): the caller's own entries; the partner's
 * once revealed to them; a tombstone where an entry was erased after they were shown it; nothing
 * for an entry they were never shown, and nothing for one still locked. **An entry whose author has
 * withdrawn it (FR-029a, BR-10a, `states.md` §9) is a tombstone from the moment the withdrawal
 * committed, whoever asks**, the withdrawer included, because there is one copy of the words and
 * the withdrawal erased it for both (T-20). Favourites are the caller's own; a reaction is the one
 * the app shows on an entry read in full; a photo or voice note is named only where the app would
 * give the caller a URL for it.
 *
 * A member who has left, or whose bond has ended, keeps their archive and gets it here (BR-9,
 * `states.md` §9): the guard asks only that they hold, or held, a membership row.
 */
interface ArchiveExports {
    /**
     * The days of [bondId]'s archive [userId] has something to see on, newest first, strictly
     * before [before] when it is given; [ExportedDays.next] is the `before` of the next page, or
     * `null` after the last.
     *
     * @throws com.moyi.common.web.NotFoundException [userId] holds no membership row in [bondId],
     * or there is no such bond: the guard's one answer.
     */
    fun page(
        userId: UUID,
        bondId: UUID,
        before: LocalDate?,
    ): ExportedDays
}

/** One page of a member's archive, and where the next begins. */
class ExportedDays(
    val days: List<ExportedDay>,
    val next: LocalDate?,
)

/** One day of the archive: its date, its status as the app shows it, and the entries the member may see. */
class ExportedDay(
    val date: LocalDate,
    /** The day's status, `bond_days.status` as the archive route answers it (`REVEALED`, `SOLO`, …). */
    val status: String,
    val entries: List<ExportedEntry>,
) {
    override fun toString(): String = "ExportedDay(date=$date, status=$status, entries=${entries.size})"
}

/**
 * One entry as the member is shown it. [text] is null on a tombstone. [toString] never prints the
 * text: these are what two people write to each other (CLAUDE.md).
 */
@Suppress("LongParameterList") // An entry's fields as the archive route sends them, each named where it is built.
class ExportedEntry(
    val id: UUID,
    /** Whether the member asking wrote it. */
    val mine: Boolean,
    /** `SUBMITTED`, `REVEALED`, or `DELETED` for a tombstone. */
    val status: String,
    val text: String?,
    val createdAt: Instant,
    val intendedAt: Instant,
    /** Whether the member asking has bookmarked it (ADR-0036). Never on a tombstone. */
    val favourited: Boolean,
    /** The reaction on it, which both members are shown (ADR-0037): the member's own on a partner's entry, the partner's on theirs. */
    val reaction: String?,
    /** The photo on it, where the app would show the member one. */
    val imageId: UUID?,
    /** The voice note on it, where the app would play the member one. */
    val voiceId: UUID?,
) {
    override fun toString(): String =
        "ExportedEntry(id=$id, mine=$mine, status=$status, text=${if (text == null) "null" else "(redacted)"})"
}
