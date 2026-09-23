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

    /** A password-reset secret that matches no reset token. */
    PASSWORD_RESET_TOKEN_INVALID,

    /** A password-reset secret that was spent or passed its one-hour TTL. */
    PASSWORD_RESET_TOKEN_EXPIRED,

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

    /** No route, or a route that exists for other methods. */
    NOT_FOUND,

    /** The path exists; this verb does not. */
    METHOD_NOT_ALLOWED,

    /** A `Content-Type` this endpoint does not read, or an `Accept` it cannot satisfy. */
    UNSUPPORTED_MEDIA_TYPE,

    /** Anything unanticipated. Never carries a message from the underlying failure. */
    INTERNAL_ERROR,
}
