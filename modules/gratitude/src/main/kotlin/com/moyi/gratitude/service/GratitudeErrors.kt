package com.moyi.gratitude.service

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import org.springframework.http.HttpStatus

// The refusals SubmitEntry gives — `bond.service.BondErrors`' own precedent,
// copied rather than shared because that file is `internal` to `bond` and
// this module cannot see it. Each extends ApiException, so the shared
// catch-all writes the RFC 9457 body and this module needs no advice of its
// own.

/**
 * BR-9: every write on a bond that has ended, whether by a leave or a block
 * (doc 26 §2.1 requires the two be indistinguishable from the other side) —
 * `bond.service.BondArchivedException`'s own wording, copied verbatim so the
 * two modules give one answer for one fact.
 *
 * [SubmitEntry] checks `membership.hasLeft` explicitly rather than leaning
 * on `membership.isOpen` alone to cover it — the second review of PR #41
 * found exactly that assumption in `RequestDeletion.cancel`, and
 * `BondMembership`'s own KDoc now says a write path that does not check
 * `isOpen` must check `left` itself. This one checks both.
 */
internal class BondArchivedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.BOND_ARCHIVED,
        "This bond has ended. It is read only now.",
    )

/**
 * `POST /bonds/{bondId}/entries` naming `imageMediaId` or `voiceMediaId`
 * (spec §1): refused rather than stored and silently ignored — Phase 4 has
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
 * BR-10: the Bond-day this entry would have landed on has already closed.
 *
 * Unreachable through any write this slice's own code produces — C1 opens a
 * day only `OPEN` or `SUSPENDED`, and closes none — but the row `SubmitEntry`
 * reads back from [com.moyi.gratitude.infra.database.BondDayStore.openOrGet]
 * may already exist and already be closed by the time a later slice's close
 * job (C3) runs alongside this one. This is that guard, in place before the
 * day that needs it exists, so a race with C3 is a `409` rather than a
 * silent third entry past BR-1's own two-entry cap.
 */
internal class DayClosedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.DAY_CLOSED,
        "That day is closed and cannot take a new entry.",
    )
