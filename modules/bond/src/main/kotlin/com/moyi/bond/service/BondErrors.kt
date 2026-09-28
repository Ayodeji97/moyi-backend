package com.moyi.bond.service

import com.moyi.bond.domain.RegionZone
import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.NotFoundException
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

// The refusals this module gives that are facts about the **caller's own**
// account, and are therefore safe to name precisely — unlike anything about a
// bond they are not in, which is always the one 404 (doc 06 §2, T-02).
//
// They extend ApiException, so the shared catch-all writes the RFC 9457 body
// and the module needs no advice of its own until it has a failure the status
// alone cannot express.

/**
 * FR-002: an unverified account may sign in and may not create or join a bond.
 *
 * Also the answer when the token's subject names nobody at all — a token we
 * signed for a user who has since been erased. The two are one response
 * because the client's move is the same and telling them apart would say
 * something about which accounts exist.
 */
internal class EmailNotVerifiedException :
    ApiException(
        HttpStatus.FORBIDDEN,
        ErrorCode.EMAIL_NOT_VERIFIED,
        "Verify your email address first.",
    )

/** FR-025's v1 abuse control, in the sentence a person would say. */
internal class BondLimitReachedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.BOND_LIMIT_REACHED,
        "You are already in three bonds. Leave one before starting another.",
    )

/** FR-022: the bond already holds everyone it can. The caller is a member, so naming it discloses nothing. */
internal class BondFullException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.BOND_FULL,
        "This bond already has both of you in it.",
    )

/**
 * BR-9: every write on a bond that has ended.
 *
 * Deliberately the same answer whether the bond ended by a leave or by a
 * block — doc 26 §2.1 requires the two be indistinguishable from the other
 * side, and a distinct code here would be the disclosure that section exists
 * to prevent.
 */
internal class BondArchivedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.BOND_ARCHIVED,
        "This bond has ended. It is read only now.",
    )

/** `POST /invites/{code}/accept` by somebody already in that bond — usually the creator scanning their own code. */
internal class AlreadyMemberException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.ALREADY_MEMBER,
        "You are already in this bond.",
    )

/**
 * **The one answer** for every way a code can fail to work: expired, revoked,
 * already used, the bond is full, it never existed, or the two accounts have
 * blocked each other (FR-024, ADR-0027).
 *
 * 404 rather than 410, and one sentence rather than six. `states.md` §2 draws
 * one screen for all of them and says why: any visible difference tells a
 * stranger that a bond exists or that a code was once real, and "ask them to
 * send you a new one" is equally true of a code that lapsed and one that was
 * never issued. The copy is that file's.
 */
internal class InviteNotUsableException :
    ApiException(
        HttpStatus.NOT_FOUND,
        ErrorCode.INVITE_NOT_USABLE,
        "That code cannot be used. Ask them to send you a new one.",
    )

/**
 * FR-027, FR-028: a proposal of that kind is already waiting for the other
 * member. The caller is a member, so naming it discloses nothing — and
 * `states.md` §8 has a screen for the pending state, which is what the client
 * should show instead of retrying.
 */
internal class ProposalPendingException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.PROPOSAL_PENDING,
        "There is already a change waiting for the other person to agree to.",
    )

/**
 * BR-6, doc 04 §8.5: the *other* member agrees. One person clicking twice is
 * not two-party consent, and this is the refusal that says so.
 */
internal class ProposalNeedsOtherMemberException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.PROPOSAL_NEEDS_OTHER_MEMBER,
        "This needs the other person to agree to it.",
    )

/**
 * FR-027's once-per-30-days rule on the anchor zone.
 *
 * The detail names the **date** it becomes allowed, which is safe: it is a fact
 * about the caller's own bond, and `states.md` §8 says the constraints belong in
 * front of a person rather than behind a retry. A date rather than a countdown,
 * for the reason that file gives about the deletion screen — a countdown framed
 * as a deadline is urgency, a date is a fact.
 *
 * `409` and not `429` (ADR-0030): a month is not a rate limit, and a client that
 * treated it as one would show "please wait" and then retry into the same wall.
 */
internal class TimezoneChangeTooSoonException(
    allowedFrom: Instant,
    zone: RegionZone,
) : ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.TIMEZONE_CHANGE_TOO_SOON,
        "The shared time zone can change again from ${DATE.format(allowedFrom.atZone(zone.zone))}.",
    ) {
    private companion object {
        /**
         * The date as a person reads it, in the bond's own zone.
         *
         * **`Locale.ENGLISH` explicitly.** `MMMM` resolves against
         * `Locale.getDefault(FORMAT)`, so without it the same API that answers
         * in English everywhere else would say "28 octobre 2026" on a container
         * whose locale happened to be French — output that varies with the
         * deployment rather than with anything the client sent. Found by the
         * review of PR #41. When doc 14's i18n arrives, this becomes the
         * *request's* locale, not the server's.
         */
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }
}

/** No live proposal of that kind: never made, already answered, cancelled, or lapsed — one answer for all four. */
internal class ProposalNotFoundException : NotFoundException("There is nothing waiting to be agreed.")
