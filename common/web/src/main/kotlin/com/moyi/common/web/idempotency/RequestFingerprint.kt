package com.moyi.common.web.idempotency

/**
 * Answers "is this the same request as the one that first used this key?"
 * without storing anything a guess could be checked against — the value V11
 * keeps in `request_hash` (ruling P8).
 *
 * **A port, because the implementation needs a secret this module cannot
 * see.** For `POST /entries` the body *is* the couple's words, and an unkeyed
 * SHA-256 of a short one ("thank you") is recoverable for the row's whole
 * 24 hours by hashing guesses — the hazard V11 retired by storing result
 * identity instead of a response body, reopened through the hash. The fix is
 * a MAC under the server's personal-data secret, which `common:security`'s
 * `PersonalDataHasher` already holds; but `common:security` depends on this
 * module, so this module cannot depend back on it. It declares what it needs
 * here and `common:security` provides the bean (`HmacRequestFingerprint`).
 * A context that imports [IdempotencyConfiguration] without one fails at
 * startup naming this type — the loud direction.
 *
 * An implementation must bind [method] and [path] as well as [body], with an
 * encoding that cannot make two different triples collide, so the stored
 * value fingerprints the *target* too — not only the bytes.
 *
 * **What a keyed fingerprint costs, accepted with ruling P8:** a retry is
 * matched only while the secret is unchanged. With an `EPHEMERAL` secret
 * (local development) a retry that crosses a restart is refused as
 * `422 IDEMPOTENCY_KEY_REUSED` rather than replayed; with a `CONFIGURED`
 * secret (every deployed environment) it is stable, and rotating it refuses
 * at most 24 hours' worth of in-flight keys, never answers one wrongly.
 */
fun interface RequestFingerprint {
    fun of(
        method: String,
        path: String,
        body: ByteArray,
    ): String
}
