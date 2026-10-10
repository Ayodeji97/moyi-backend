package com.moyi.common.security

import com.moyi.common.web.RepresentationDigest

/**
 * What an `ETag` is made of, as an HMAC-SHA256 under the personal-data
 * secret: `common:web`'s [RepresentationDigest] port, implemented here
 * because this is the module that holds the secret and `common:web` cannot
 * depend on it. [HmacRequestFingerprint] is the same arrangement for the
 * same reason.
 *
 * **Keyed for the reason [PersonalDataHasher] is.** A response body can be
 * what two people wrote to each other, and its tag is a header: it reaches
 * logs and proxies the body never does. A plain SHA-256 of a short entry in
 * a body whose every other field is known is a guess away from the entry.
 * Under a MAC the guess cannot be checked without the secret.
 *
 * **The existing secret, not a new one**, so there is nothing more to
 * configure, rotate or forget. What keeps this use apart from the others
 * made of the same key is [DOMAIN], which is put before the body: a tag is
 * never the fingerprint or the address hash of the same bytes, so a value
 * seen in one place says nothing about a value stored in another.
 *
 * What a keyed tag costs is on [RepresentationDigest]: it validates only
 * while the secret is unchanged.
 */
class HmacRepresentationDigest(
    private val hasher: PersonalDataHasher,
) : RepresentationDigest {
    override fun of(body: ByteArray): String = hasher.mac(DOMAIN + body).toHexString()

    internal companion object {
        /** Ends in a line feed, which no other input MACed under this secret begins with. */
        val DOMAIN: ByteArray = "moyi-etag-v1\n".toByteArray(Charsets.US_ASCII)
    }
}
