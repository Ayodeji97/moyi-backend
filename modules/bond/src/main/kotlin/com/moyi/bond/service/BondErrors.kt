package com.moyi.bond.service

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import org.springframework.http.HttpStatus

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
