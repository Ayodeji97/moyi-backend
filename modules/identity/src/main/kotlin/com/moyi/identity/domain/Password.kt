package com.moyi.identity.domain

import java.text.Normalizer

/**
 * A password as the user typed it, normalised and checked against the policy
 * in ADR-0012 — and never printed.
 *
 * Constructed only through [of], because normalisation has to happen *before*
 * validation and a plain constructor would let unvalidated input reach the
 * encoder. The type is the guarantee: a function taking a [Password] cannot
 * be handed a raw string that nobody checked.
 */
@JvmInline
internal value class Password private constructor(
    val value: String,
) {
    /** Bytes, not characters — the only measure the encoder actually cares about. */
    val octetLength: Int get() = value.toByteArray(Charsets.UTF_8).size

    override fun toString(): String = "Password(redacted)"

    companion object {
        /**
         * **Eight, as ADR-0012 decided — and only now, because the other half
         * of that decision is finally here.**
         *
         * ADR-0012 lowers the floor to 8 and says in terms that the shorter
         * minimum is *paid for* by widening the breached-password corpus from
         * the top 10k to the top ~10M, and that "the two halves are one
         * decision and MUST NOT be unbundled". This number sat at the pre-ADR
         * 12 for four PRs, with `PasswordTest` pinning it there, so that
         * shipping the friction benefit without the security half would be a
         * failing test rather than a judgement call. The corpus landed in
         * ADR-0016; [BreachedPasswordCorpus] is the check; this is the number
         * moving on the same day, which is what the ADR asked for.
         *
         * Eight is not a claim that eight characters is a good password. It is
         * the observation that length is the *weakest* of the four controls
         * here — human-chosen passwords carry ~2–3 bits of entropy per
         * character, so 8 and 12 are both trivially brute-forceable offline,
         * and what actually separates them is Argon2id, which we already have.
         * Against credential stuffing, which ADR-0012 calls the dominant real
         * attack, length does almost nothing and the corpus does almost
         * everything.
         */
        const val MIN_LENGTH = 8

        /** NIST SP 800-63B requires accepting at least 64; ADR-0012 sets 128. */
        const val MAX_LENGTH = 128

        /**
         * FR-001's third limit. A 128-character maximum bounds *characters*,
         * and a character can be four bytes — so without this, a 128-character
         * password of astral-plane emoji is a 512-byte input, and the
         * character limit alone is not the bound on work it looks like.
         */
        const val MAX_OCTETS = 512

        /**
         * **NFKC, before anything else.** The same password typed on two
         * keyboards can be two different byte sequences — a precomposed "é"
         * versus "e" plus a combining accent — which hash differently and
         * would lock the user out of their own account on their other device.
         * Normalising at the boundary, once, is what makes "the same password"
         * mean the same thing everywhere. It has to happen *before* the length
         * check, because normalisation changes length.
         */
        fun of(raw: String): Password {
            val normalised = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            val password = Password(normalised)

            // No composition rules — ADR-0012, and they reduce entropy in
            // practice by pushing people towards `Password1!`. Length and byte
            // count are the questions this type asks; whether the password is
            // already known to attackers is [BreachedPasswordCorpus]'s, asked
            // at the same edge by the same annotation. See
            // `PasswordConstraintValidator` for why that one is not here.
            require(normalised.length >= MIN_LENGTH) { "password must be at least $MIN_LENGTH characters" }
            require(normalised.length <= MAX_LENGTH) { "password must be at most $MAX_LENGTH characters" }
            require(password.octetLength <= MAX_OCTETS) { "password must be at most $MAX_OCTETS bytes" }

            return password
        }
    }
}

/**
 * Turns a [Password] into something safe to store. The port; the Argon2id
 * implementation lives in `infra.security`.
 *
 * It reports [algorithm] rather than assuming it, so the value written to
 * `credentials.algorithm` comes from whatever actually did the hashing
 * instead of from a constant that can drift away from it.
 */
internal interface PasswordHasher {
    val algorithm: PasswordHashAlgorithm

    /**
     * @throws HashingCapacityExceededException when too many hashes are already in flight (NFR-005a).
     */
    fun hash(password: Password): PasswordHash

    /** Verifies an untrusted login input against a stored digest. */
    fun matches(
        raw: String,
        hash: PasswordHash,
    ): Boolean

    /** Performs the same Argon2 work when no account exists, closing T-18's timing oracle. */
    fun matchesDummy(raw: String): Boolean
}

/**
 * Too many passwords are being hashed at once (NFR-005a).
 *
 * A plain domain exception carrying no HTTP status: the `domain` layer
 * depends on nothing, and a status code is a fact about a protocol this
 * layer has never heard of. `identity.web` is what turns it into a 503.
 */
internal class HashingCapacityExceededException : RuntimeException("Password hashing is at capacity")
