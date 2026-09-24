package com.moyi.common.web

/**
 * The stable, machine-readable half of an error response (doc 06 §2).
 *
 * `code` is what a client switches on; `detail` is prose and may be reworded
 * or localised at any time without it being a breaking change. That division
 * is the whole point — a client that pattern-matches on English is a client
 * that breaks when someone improves a sentence.
 *
 * One enum for the whole application rather than one per module, because doc
 * 06 requires the set to be **enumerated and exhaustive**: it is generated
 * into the client as a sealed class so that an unhandled code is a compile
 * error there. Two registries cannot make that promise. `common:web` depends
 * on no module, so collecting them here couples nothing.
 *
 * Codes are added by the slice that can return them. An unused code is a
 * client handling a case the server never produces.
 */
enum class ErrorCode {
    /** The body could not be parsed at all — not JSON, or not the right shape. */
    MALFORMED_REQUEST,

    /** The body parsed but a field is not acceptable. Always accompanied by `errors`. */
    VALIDATION_FAILED,

    /**
     * Password hashing is at capacity (NFR-005a). Deliberately distinct from a
     * generic 503: this one is self-inflicted load, it clears in milliseconds,
     * and the client should retry rather than show a failure.
     */
    HASHING_CAPACITY_EXCEEDED,

    /**
     * `POST /auth/verify-email` with a token that matches nothing (FR-002).
     * A mistyped or truncated link, or a guess. 422: the body is well-formed,
     * the value in it is not one this system issued.
     */
    VERIFICATION_TOKEN_INVALID,

    /**
     * The token was real but is spent — past its 24 hours, or presented
     * before. One code for both because the person's recovery is the same
     * and `states.md` §1 draws one state for it: ask for a new link. 410.
     */
    VERIFICATION_TOKEN_EXPIRED,

    /**
     * `POST /auth/login` refused. One code for a wrong password, an unknown
     * address, a locked account and an account in a state that cannot sign in,
     * because telling them apart is exactly the oracle T-18 and ADR-0015 close.
     * 401, without `WWW-Authenticate`: no bearer credential was presented.
     */
    INVALID_CREDENTIALS,

    /**
     * `POST /auth/refresh` with a token that is unknown, expired or revoked. The
     * client's only correct response is to sign in again. Distinct from
     * [TOKEN_REUSE_DETECTED] because that one asks the client to do more.
     */
    REFRESH_TOKEN_INVALID,

    /**
     * `POST /auth/refresh` with a token that was already rotated (doc 06 §3.1,
     * doc 09 §3): somebody presented a stolen copy, or the victim did after the
     * thief. The whole family is revoked before this is returned, and doc 13
     * has the client wipe its credentials and explain why — which is why this
     * needs its own code.
     */
    TOKEN_REUSE_DETECTED,

    /** A password-reset secret that matches no reset token. */
    PASSWORD_RESET_TOKEN_INVALID,

    /** A password-reset secret that was spent or passed its one-hour TTL. */
    PASSWORD_RESET_TOKEN_EXPIRED,

    /**
     * FR-002: "unverified accounts may sign in but MUST NOT create or join a
     * Bond." 403 rather than 401 — the token is perfectly valid, the account
     * simply lacks the authority — and rather than 422, because nothing about
     * the request is wrong. The client's move is `states.md` §1's screen 3,
     * "Check again".
     */
    EMAIL_NOT_VERIFIED,

    /**
     * FR-025: a fourth open bond. 409, a state conflict rather than a
     * validation failure, and safe to be specific about because it is a fact
     * about the caller's own account, not about anyone else's bond.
     */
    BOND_LIMIT_REACHED,

    /**
     * No usable bearer token: missing, malformed, expired, signed by the
     * wrong key, for the wrong audience, or issued before the user's sessions
     * were revoked. One code, deliberately — which of those it was is in the
     * server log, and a client's only correct response to any of them is the
     * same: refresh, or sign in again.
     */
    UNAUTHENTICATED,

    /** A valid token that lacks the authority this action needs. */
    FORBIDDEN,

    /**
     * A bucket in doc 06 §4 is empty (FR-012). 429, always with `Retry-After`
     * and the `X-RateLimit-*` triple; `states.md` §1b: the client announces
     * the wait, never the failure.
     */
    RATE_LIMITED,

    /**
     * FR-022: the bond already has everyone it can hold, so there is nothing
     * to invite anyone to. Safe to name, because the caller is a member and
     * already knows who is in it.
     */
    BOND_FULL,

    /**
     * BR-9: every write on an `ARCHIVED` or `PENDING_DELETION` bond. Safe to
     * name for the same reason — and deliberately the *same* code whether the
     * bond ended by a leave or by a block, because doc 26 §2.1 requires that
     * the two be indistinguishable from the other side.
     */
    BOND_ARCHIVED,

    /**
     * `POST /invites/{code}/accept` by someone already in that bond — the
     * creator scanning their own code, most often. A fact about the caller's
     * own memberships, which they can already list, so naming it discloses
     * nothing.
     */
    ALREADY_MEMBER,

    /**
     * **One code for six causes, on purpose** (FR-024, ADR-0027): the invite
     * expired, was revoked, was already used, the bond is full, the code never
     * existed, or the two accounts have blocked each other. `states.md` §2
     * draws one screen and one string for all of them, because any visible
     * difference between them tells a stranger that a bond exists, or that a
     * code was once real. 404, never 410 — doc 06 §3.3's `410` for a spent
     * invite is superseded, since "gone" confirms it was once here.
     */
    INVITE_NOT_USABLE,

    /** No route, or a route that exists for other methods. */
    NOT_FOUND,

    /** The path exists; this verb does not. */
    METHOD_NOT_ALLOWED,

    /** A `Content-Type` this endpoint does not read, or an `Accept` it cannot satisfy. */
    UNSUPPORTED_MEDIA_TYPE,

    /** Anything unanticipated. Never carries a message from the underlying failure. */
    INTERNAL_ERROR,
}
