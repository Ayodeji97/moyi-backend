package com.moyi.common.web

import org.springframework.http.HttpStatus

/**
 * An `If-Match` header, parsed (RFC 9110 §13.1.1).
 *
 * Doc 06 §1 requires one on every bond-settings update, and the reason is the
 * lost update: two members open the settings screen, both change the name, and
 * without a condition the second write silently erases the first — with no
 * error for either of them to see. Entity tags are opaque strings;
 * each resource supplies its current validator under its update lock.
 *
 * **Two deliberate deviations from the RFC, both recorded in ADR-0029:**
 *
 * - **`*` is refused** with `428` rather than matching. The RFC says `*` matches
 *   any current representation, so a compliant server would perform the write.
 *   But the *point* of requiring the condition is to prevent a lost update, and
 *   `If-Match: *` is a request to skip that check — honouring it would make the
 *   requirement decorative. A client that genuinely means to overwrite re-reads
 *   and sends the version it saw.
 * - **An unparseable validator matches nothing**, producing `412` rather than a
 *   `400` about the header's shape. The asymmetry is the argument: a false "does
 *   not match" costs a client one retry, and a false "matches" is a write lost
 *   in silence. So every doubt resolves against the writer.
 *
 * A weak validator (`W/"3"`) never matching *is* the RFC — `If-Match` uses the
 * strong comparison function, and a weak validator cannot be strongly compared.
 */
@JvmInline
value class IfMatch private constructor(
    private val tags: Set<String>,
) {
    /** Strong comparison is byte equality, never numeric coercion. */
    fun matches(etag: String): Boolean = etag in tags

    fun matches(version: Int): Boolean = matches("\"$version\"")

    companion object {
        private const val ENTITY_TAG = "(?:W/)?\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\""
        private val tag = Regex(ENTITY_TAG)
        private val list = Regex("[ \t]*(?:$ENTITY_TAG)?(?:[ \t]*,[ \t]*(?:$ENTITY_TAG)?)*[ \t]*")

        /** Missing conditions are 428; malformed lists fail closed with 412. */
        fun parse(header: String?): IfMatch {
            if (header.isNullOrBlank() || header.trim() == "*") throw PreconditionRequiredException()
            // Commas inside an opaque tag are data, not list delimiters. Validate
            // the entire field before accepting any individual strong tag.
            if (!list.matches(header)) return IfMatch(emptySet())
            return IfMatch(
                tag
                    .findAll(header)
                    .map { it.value }
                    .filterNot { it.startsWith("W/") }
                    .toSet(),
            )
        }
    }
}

/**
 * 428: doc 06 §1 requires this update to be conditional, and it was not.
 *
 * Not a `400`. The request is well formed; what is missing is a precondition,
 * and RFC 6585 added this status for exactly that difference — "the origin
 * server requires the request to be conditional".
 */
class PreconditionRequiredException :
    ApiException(
        HttpStatus.PRECONDITION_REQUIRED,
        ErrorCode.PRECONDITION_REQUIRED,
        "Read this first, then send its version back as If-Match.",
    )

/**
 * 412: the version the caller holds is not the current one.
 *
 * Also the answer when two writers both pass the header check and one loses the
 * race at flush — from the loser's side the precondition had stopped being true,
 * which is the same fact arriving a moment later (ADR-0029).
 *
 * The detail is written in what a person experiences rather than in HTTP: what
 * they have to do is re-open the thing and try again.
 */
class PreconditionFailedException :
    ApiException(
        HttpStatus.PRECONDITION_FAILED,
        ErrorCode.PRECONDITION_FAILED,
        "This has changed since you last read it. Open it again and retry.",
    )
