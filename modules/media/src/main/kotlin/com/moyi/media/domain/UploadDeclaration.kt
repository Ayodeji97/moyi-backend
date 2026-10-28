package com.moyi.media.domain

import java.util.Base64

/**
 * What an owner says they are about to upload (doc 06:183): the kind, the
 * content type, the exact length and the SHA-256 of the bytes. Every value is
 * signed into the upload URL, so the storage refuses a body that differs from
 * the declaration (ADR-0047).
 *
 * Built only by [UploadDeclaration.of], which is where FR-042 and FR-043 are
 * applied, in this order, so that each request has one answer:
 *
 * 1. a missing or malformed field: [Refusal.Invalid] (422), every field named;
 * 2. a content type its kind does not accept: [Refusal.UnsupportedType] (415);
 * 3. more bytes than its kind allows: [Refusal.TooLarge] (413).
 */
internal class UploadDeclaration private constructor(
    val kind: MediaKind,
    /** Lower case, without parameters: the exact value signed into the URL and sent back as a required header. */
    val contentType: String,
    val sizeBytes: Long,
    /** The 32-byte SHA-256 in standard base64, re-encoded from the decoded bytes so it is canonical. */
    val checksumSha256: String,
) {
    override fun toString(): String = "UploadDeclaration(kind=$kind, contentType=$contentType, sizeBytes=$sizeBytes)"

    /** One field that is missing or malformed; never carries the value. */
    data class Violation(
        val field: String,
        val code: String,
        val message: String,
    )

    sealed interface Refusal {
        data class Invalid(
            val violations: List<Violation>,
        ) : Refusal

        data object UnsupportedType : Refusal

        data class TooLarge(
            val maxBytes: Long,
        ) : Refusal
    }

    sealed interface Outcome {
        data class Accepted(
            val declaration: UploadDeclaration,
        ) : Outcome

        data class Refused(
            val refusal: Refusal,
        ) : Outcome
    }

    companion object {
        private const val SHA256_BYTES = 32

        // One return per refusal, in the order the class KDoc gives: the order is the rule.
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun of(
            kind: String?,
            contentType: String?,
            sizeBytes: Long?,
            checksum: String?,
        ): Outcome {
            val violations = mutableListOf<Violation>()
            val parsedKind =
                when {
                    kind == null -> null.also { violations += Violation("kind", "NOT_NULL", "is required") }
                    else -> MediaKind.named(kind) ?: null.also { violations += Violation("kind", "VALID_KIND", "must be IMAGE or VOICE") }
                }
            val type = contentType?.trim()?.lowercase()
            if (type.isNullOrEmpty()) violations += Violation("contentType", "NOT_BLANK", "is required")
            when {
                sizeBytes == null -> violations += Violation("sizeBytes", "NOT_NULL", "is required")
                sizeBytes < 1 -> violations += Violation("sizeBytes", "POSITIVE", "must be at least 1")
            }
            val digest = checksum?.let(::decodedSha256)
            when {
                checksum == null -> violations += Violation("checksum", "NOT_NULL", "is required")
                digest == null -> violations += Violation("checksum", "SHA256_BASE64", "must be a SHA-256 digest in base64")
            }
            if (violations.isNotEmpty()) return Outcome.Refused(Refusal.Invalid(violations))

            val declaredKind = checkNotNull(parsedKind)
            val declaredType = checkNotNull(type)
            val declaredSize = checkNotNull(sizeBytes)
            if (declaredType !in declaredKind.contentTypes) return Outcome.Refused(Refusal.UnsupportedType)
            if (declaredSize > declaredKind.maxUploadBytes) return Outcome.Refused(Refusal.TooLarge(declaredKind.maxUploadBytes))
            val canonical = Base64.getEncoder().encodeToString(checkNotNull(digest))
            return Outcome.Accepted(UploadDeclaration(declaredKind, declaredType, declaredSize, canonical))
        }

        private fun decodedSha256(value: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(value) }.getOrNull()?.takeIf { it.size == SHA256_BYTES }
    }
}
