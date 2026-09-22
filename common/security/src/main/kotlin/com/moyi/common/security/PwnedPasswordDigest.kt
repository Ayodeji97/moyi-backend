package com.moyi.common.security

import java.security.MessageDigest
import java.text.Normalizer

/**
 * The lookup key the Have I Been Pwned corpus is keyed on.
 *
 * ## Why SHA-1, and why that is not the weakness it looks like
 *
 * HIBP publishes its corpus as SHA-1 digests, so the algorithm is fixed by the
 * data rather than chosen by us. Static analysis flags
 * `MessageDigest.getInstance("SHA-1")` wherever it appears — correctly, as a
 * rule — and it is worth being precise about why the rule does not bite here:
 *
 * - **Nothing produced here is stored, transmitted, or used to authenticate
 *   anyone.** The digest exists for the microseconds it takes to index into a
 *   bit array and is then discarded. Passwords are stored as Argon2id
 *   (NFR-046); this is a set-membership key, not a credential.
 * - **SHA-1's break is collision resistance**, which an attacker would use to
 *   find a *second* password hashing to the same value. The only thing that
 *   would buy is a false positive — a safe password rejected — against an
 *   attacker who can already pick their own password. The filter concedes one
 *   in a thousand of those by design.
 * - **Preimage resistance is what would matter**, and SHA-1 retains it. Even
 *   if it did not, the corpus is a public list of already-leaked passwords.
 *
 * ## Why this is one function and not three
 *
 * The corpus builder needs it to check its own output, the service needs it on
 * every registration, and the test fixture needs it to build a filter the
 * service can read. Three copies that must agree is the same drift risk that
 * put [BloomFilter] here rather than in either module — and the failure mode is
 * worse, because a digest computed two different ways does not throw. Every
 * lookup simply misses, and the breached-password check is off while reporting
 * that it is on.
 */
object PwnedPasswordDigest {
    /**
     * The 20-byte SHA-1 of [password], NFKC-normalised first.
     *
     * **Normalisation before hashing, always**, because that is what the rest
     * of the system means by "the same password": the precomposed and
     * decomposed spellings of an accented character are different bytes and
     * must not be different passwords. Doing it here rather than at each call
     * site is the point — a caller that normalised and one that did not would
     * disagree about a password without either of them failing.
     *
     * One honest limitation: the corpus itself is *not* normalised, so a
     * breached password whose only spelling in the wild is decomposed would
     * not match. Essentially every entry in the corpus is ASCII, where NFKC is
     * the identity function, so this is theoretical rather than practical.
     */
    @Suppress("InsecureHash")
    fun of(password: String): ByteArray =
        MessageDigest
            .getInstance(ALGORITHM)
            .digest(Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8))

    /** Not configurable: it is a property of HIBP's published data, not a choice. */
    const val ALGORITHM = "SHA-1"
}
