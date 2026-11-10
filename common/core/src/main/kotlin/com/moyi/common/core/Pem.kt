package com.moyi.common.core

/**
 * The base64 inside a PEM block, whatever the trip through a vault and an
 * environment variable did to its line breaks.
 *
 * A key reaches the container in more shapes than `openssl` writes: real
 * newlines; `\n` spelt out, as Google's service-account JSON holds it; `\\n`,
 * because Kamal writes its env file with Ruby's `String#dump`, which doubles
 * every backslash, and docker reads that file literally (the 2026-11-10 deploy
 * stopped on exactly this); or no breaks at all. Every one of them is the same
 * key, so all are read the same way here, once, for both the JWT signing key
 * (ADR-0019) and the FCM service account (ADR-0044).
 *
 * An escaped break is removed whole, backslash and letter together: base64
 * holds `n` and `r`, so removing only the backslashes would leave a letter in
 * the key. A backslash left on its own is removed too: it is never base64.
 * Anything else stays, for the decoder to refuse.
 */
object Pem {
    private val escapedBreak = Regex("""\\[nr]""")
    private val strayBackslashOrSpace = Regex("""[\\\s]""")

    /**
     * The base64 between `-----BEGIN [kind]-----` and `-----END [kind]-----`,
     * without line breaks, or `null` when either armour line is missing. It
     * may be empty, or not base64: the caller decodes it and words the refusal.
     */
    fun body(
        pem: String,
        kind: String,
    ): String? {
        val header = "-----BEGIN $kind-----"
        val footer = "-----END $kind-----"
        if (!pem.contains(header) || !pem.contains(footer)) return null
        return pem
            .substringAfter(header)
            .substringBefore(footer)
            .replace(escapedBreak, "")
            .replace(strayBackslashOrSpace, "")
    }
}
