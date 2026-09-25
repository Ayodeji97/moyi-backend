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
