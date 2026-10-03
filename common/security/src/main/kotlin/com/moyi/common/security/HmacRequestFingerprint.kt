package com.moyi.common.security

import com.moyi.common.web.idempotency.RequestFingerprint
import java.io.ByteArrayOutputStream

/**
 * `idempotency_keys.request_hash` (V11), as an HMAC-SHA256 under the
 * personal-data secret — `common:web`'s [RequestFingerprint] port,
 * implemented here because this is the module that holds the secret and
 * `common:web` cannot depend on it (ruling P8).
 *
 * **Keyed for the reason [PersonalDataHasher] is.** For `POST /entries` the
 * request body *is* the couple's words. A plain SHA-256 of a short entry is
 * a guess away from the entry: anyone who can read the table can hash
 * "thank you" and compare, for the row's whole 24 hours. Under a MAC the
 * guess cannot be checked without the secret.
 *
 * **Method and path are inside the MAC, framed so no two triples collide.**
 * Each is prefixed with its own byte length (`4:POST`, `31:/api/v1/…`), and
 * the body is whatever follows — so `("PO", "ST/x")` and `("POST", "/x")`
 * are different inputs, and a body cannot pose as the tail of a path. The
 * body goes in as the bytes received, never decoded to text first: two
 * different byte sequences must not meet at one replacement character.
 *
 * What a keyed fingerprint costs is on [RequestFingerprint]: it matches a
 * retry only while the secret is unchanged (`EPHEMERAL` secrets do not
 * survive a restart; rotation refuses at most 24 hours of in-flight keys).
 */
class HmacRequestFingerprint(
    private val hasher: PersonalDataHasher,
) : RequestFingerprint {
    override fun of(
        method: String,
        path: String,
        body: ByteArray,
    ): String {
        val framed = ByteArrayOutputStream(body.size + FRAMING_HEADROOM)
        listOf(method, path).forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            framed.write("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            framed.write(bytes)
        }
        framed.write(body)
        return hasher.hash(framed.toByteArray())
    }

    private companion object {
        const val FRAMING_HEADROOM = 128
    }
}
