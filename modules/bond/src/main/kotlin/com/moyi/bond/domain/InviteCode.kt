package com.moyi.bond.domain

import java.util.Random

/**
 * A six-character invite code (FR-022).
 *
 * The alphabet is `states.md` §2's, decided 2026-09-03: the digits 2–9 and
 * the letters A–Z **less** `0`/`O` and `1`/`I`/`L`, which are the pairs people
 * mis-transcribe when reading a code aloud down a phone line — precisely how
 * a long-distance couple will share one — and less `U`, so six random
 * characters cannot spell something unfortunate. Thirty symbols, so 30⁶ ≈
 * 7.3 × 10⁸ codes, about 29.4 bits.
 *
 * **Doc 09 T-06 says 32 symbols and ~10⁹; it is the stale number** and
 * ADR-0026 amends it. The size of the space is not what makes brute force
 * impractical anyway — doc 06 §4's per-IP limit on lookup and accept is, and
 * that lands with slice B2.
 *
 * Always upper case inside the system. V9's CHECK constraint restates the
 * alphabet, so a code outside it cannot reach the table by any path.
 */
@JvmInline
internal value class InviteCode(
    val value: String,
) {
    init {
        // Length first: "must be 6 characters" is the more useful sentence for
        // a truncated paste, and it would otherwise be reported as a bad
        // character somewhere the person cannot see.
        require(value.length == LENGTH) { "must be $LENGTH characters" }
        require(value.all { it in ALPHABET }) { EXCLUDED_CHARACTER_MESSAGE }
    }

    companion object {
        const val LENGTH = 6

        /** `states.md` §2, 2026-09-03. Thirty symbols; see the class KDoc for which are missing and why. */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

        /**
         * `states.md` §2 asks that the excluded characters be *rejected with a
         * clear message* rather than silently mapped — mapping `0` to `O`
         * would guess at what the person meant, and a wrong guess spends
         * somebody's single-use invite.
         */
        const val EXCLUDED_CHARACTER_MESSAGE = "may only use the digits 2 to 9 and letters A to Z, never 0, O, 1, I, L or U"

        /**
         * Normalises what a person typed. The entry field capitalises
         * automatically (`states.md` §2), but a pasted link or a lowercase
         * transcription must still resolve, so input is case-insensitive.
         */
        fun parse(raw: String): InviteCode = InviteCode(raw.trim().uppercase())

        /**
         * Six uniformly chosen symbols.
         *
         * [random] is a parameter rather than a captured `SecureRandom` for the
         * reason `IdGenerator` and `Clock` are ports: production passes a
         * CSPRNG, and a test passes a seeded generator so it can name the code
         * it expects instead of matching a pattern.
         */
        fun random(random: Random): InviteCode = InviteCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))
    }
}

/**
 * Where a fresh code comes from. A port, so the domain states the need and
 * `infra.security.SecureInviteCodes` supplies the CSPRNG — the domain layer
 * depends on nothing (doc 25 §6).
 */
internal fun interface InviteCodes {
    fun next(): InviteCode
}
