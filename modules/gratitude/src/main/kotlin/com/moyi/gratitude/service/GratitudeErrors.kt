package com.moyi.gratitude.service

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.NotFoundException
import org.springframework.http.HttpStatus

// The refusals SubmitEntry and ChangeEntry give — `bond.service.BondErrors`' own precedent,
// copied rather than shared because that file is `internal` to `bond` and
// this module cannot see it. Each extends ApiException, so the shared
// catch-all writes the RFC 9457 body and this module needs no advice of its
// own.

/**
 * BR-9: a new entry or an edit on a bond that has ended, whether by a leave
 * or a block (doc 26 §2.1 requires the two be indistinguishable from the
 * other side) — `bond.service.BondArchivedException`'s own wording, copied
 * verbatim so the two modules give one answer for one fact.
 *
 * **Not an author's delete of their own entry**, which [ChangeEntry] allows
 * on an ended bond and says why.
 *
 * [SubmitEntry] and [ChangeEntry] check `membership.hasLeft` explicitly
 * rather than leaning on `membership.isOpen` alone to cover it — the second
 * review of PR #41 found exactly that assumption in `RequestDeletion.cancel`, and
 * `BondMembership`'s own KDoc now says a write path that does not check
 * `isOpen` must check `left` itself. Both check both.
 */
internal class BondArchivedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.BOND_ARCHIVED,
        "This bond has ended. It is read only now.",
    )

/**
 * `POST /bonds/{bondId}/entries` or `PATCH /entries/{entryId}` naming
 * `imageMediaId` or `voiceMediaId` (spec §1): refused rather than stored and silently ignored — Phase 4 has
 * not built anywhere for either to go yet, and accepting the field only to
 * drop it on the floor would be a promise this response cannot keep.
 */
internal class MediaNotYetSupportedException :
    ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ErrorCode.MEDIA_NOT_YET_SUPPORTED,
        "Photos and voice notes are not supported yet. Write a few words instead.",
    )

/**
 * BR-2: a second entry from the same member on the same Bond-day.
 *
 * Raised only from `entries_one_per_member_per_day` (V12) rejecting the
 * insert, never from a read-before-write check — see [SubmitEntry]'s own
 * KDoc for why a check-then-insert here would be a race, not a guarantee.
 */
internal class EntryAlreadyExistsException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.ENTRY_ALREADY_EXISTS,
        "You have already written today's entry for this bond.",
    )

/**
 * BR-10: the Bond-day this entry would have landed on is settled — closed,
 * or already revealed.
 *
 * One request reaches it by itself: a `REVEALED` day is settled, so an
 * author who deletes their entry after the reveal and writes that day again
 * is refused here — which is what stops delete-then-rewrite from replacing
 * words already read. The other way in is a race with the close job, which
 * takes the bond lock before the row `SubmitEntry` reads back from
 * [com.moyi.gratitude.infra.database.BondDayStore.openOrGet] may already
 * be closed by a run alongside it, and that race is a `409` rather than a
 * silent entry on a settled day, past BR-2's one per member per day.
 */
internal class DayClosedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.DAY_CLOSED,
        "That day is closed and cannot take a new entry.",
    )

/**
 * BR-7: the partner may already have read these words — or the author has
 * erased them. The sentence says neither: it answers both, and "has been
 * revealed" was false for an entry deleted before anybody else could read it.
 *
 * Also what a bookmark is refused with on a tombstone ([FavouriteEntry]), so
 * the sentence says "changed" and not "edited": it has to be true of an edit
 * and of a mark alike. One code for "this entry is settled and takes no more
 * changes" is worth more to a client than a second one.
 */
internal class EntryImmutableException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.ENTRY_IMMUTABLE,
        "This entry can no longer be changed.",
    )

/**
 * FR-093: the caller's own entry cannot be bookmarked before it has been
 * revealed. Raised only for an entry the caller wrote, by
 * [FavouriteEntry]; a partner's unrevealed entry is [EntryNotFoundException]
 * to them, as it is on every route (`ErrorCode.ENTRY_NOT_REVEALED` has why).
 *
 * The sentence says only that, and promises nothing about when: "once both
 * of you have written" was untrue of an entry on a solo day that closed after
 * the bond had ended, which never will be revealed, and of a bond still
 * waiting for its second member.
 */
internal class EntryNotRevealedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.ENTRY_NOT_REVEALED,
        "This entry has not been revealed.",
    )

/**
 * The one answer for an entry the caller may not act on (spec §5.2, T-02):
 * no such id, an id that is not a UUID, an entry in a bond the caller is not
 * in; on the routes that change an entry, an entry the caller's partner
 * wrote; and on the favourite routes, an entry the caller was never shown
 * (locked, or erased before it was revealed), where a partner's revealed
 * entry is theirs to mark (ADR-0036 decision 11). One class, so the `404`s
 * are the same bytes because they are the same object — not because eight
 * copies of a sentence happen to agree
 * (`bond.service.BondNotFoundException`'s own shape).
 */
internal class EntryNotFoundException : NotFoundException("That entry was not found.")

/**
 * The one answer for a date that is not in the caller's archive
 * (`ErrorCode.DAY_NOT_FOUND` has the reasons, and why they are one).
 *
 * One class with no argument, so that every way of getting here is the same
 * object's bytes: the route cannot come to say more for one reason than for
 * another. The sentence is true of all of them, a string that is not a date
 * included, and it does not repeat what was asked.
 */
internal class DayNotFoundException :
    ApiException(
        HttpStatus.NOT_FOUND,
        ErrorCode.DAY_NOT_FOUND,
        "That day was not found.",
    )

/** A competing bookmark operation has not finished; no successful removal is promised. */
internal class FavouriteBusyException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.FAVOURITE_BUSY,
        "This bookmark is being changed. Please try again.",
    )
