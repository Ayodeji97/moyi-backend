package com.moyi.identity.api

/**
 * Password hashing and the breached-password check, for the one caller that
 * keeps credentials of its own: the `admin` module (admin console A1).
 *
 * It delegates to the same Argon2id hasher and the same corpus the account
 * path uses, so there is one definition of "a hash" and one of "breached".
 * Hashes cross this boundary as the encoded string, and nothing here logs or
 * prints a raw password.
 */
interface PasswordHashing {
    /** @throws IllegalArgumentException when [raw] breaks the password shape rules (length, bytes). */
    fun hash(raw: String): String

    fun matches(
        raw: String,
        hash: String,
    ): Boolean

    /** The same Argon2 work as [matches], when no account exists (T-18's timing oracle). */
    fun matchesDummy(raw: String): Boolean

    /** Whether [raw] is in the breached-password corpus. False for a string that breaks the shape rules. */
    fun isBreached(raw: String): Boolean
}
