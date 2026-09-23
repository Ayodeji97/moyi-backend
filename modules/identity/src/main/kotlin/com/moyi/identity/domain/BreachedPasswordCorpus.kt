package com.moyi.identity.domain

/**
 * Whether a password is already known to attackers (FR-001, T-03, ADR-0012).
 *
 * The port, in the layer that depends on nothing: a 17 MB Bloom filter loaded
 * from a file is an infrastructure fact, and the rule — *we do not accept a
 * password that is in the corpus* — is a domain one. The implementation lives
 * in `identity.infra.security`.
 *
 * **This is the control that matters most of the four protecting an account.**
 * ADR-0012's table puts credential stuffing as "the dominant real-world
 * attack", and length as the weakest defence against it. Argon2id protects a
 * leaked hash table, rate limits protect against online guessing, and neither
 * does anything about an attacker who already has the correct password from
 * somewhere else. Only this does.
 */
internal interface BreachedPasswordCorpus {
    /**
     * True if [password] appears in the corpus.
     *
     * **A Bloom filter, so this is one-sided.** `false` is certain — the
     * password is genuinely absent. `true` is right roughly 999 times in 1000,
     * and otherwise rejects a password nobody has ever breached. That is the
     * right way round for a security control, and it is why the message shown
     * to a user says the password *matches a list* rather than asserting a
     * fact about their password that is occasionally untrue.
     */
    fun contains(password: Password): Boolean
}
