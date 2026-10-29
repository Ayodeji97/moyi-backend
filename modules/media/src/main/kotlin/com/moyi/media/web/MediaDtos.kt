package com.moyi.media.web

import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.service.OwnAsset
import com.moyi.media.service.UploadIntent
import java.time.Instant
import java.util.UUID

/**
 * `POST /media/upload-intent`'s body (doc 06:181). Every field is nullable here
 * and checked by the domain's `UploadDeclaration`, the one place FR-042 and
 * FR-043 are written: a missing field, or a well-formed value that is not
 * acceptable (an unknown kind, a size below 1, a checksum that is not a
 * SHA-256), is `422` naming the field. A body that is not JSON, or a field of
 * the wrong JSON type (a size that is not a number), cannot be read at all and
 * is `400 MALFORMED_REQUEST`, as everywhere (review of #106).
 *
 * @property checksum the SHA-256 of the bytes about to be uploaded, in standard base64 (44 characters)
 * @property sizeBytes the exact length of the upload; the URL is signed for this length and no other
 */
internal data class UploadIntentRequest(
    val kind: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val checksum: String? = null,
)

/**
 * The upload URL and what to send with it (doc 06:181). `PUT` the bytes to
 * [uploadUrl] before [expiresAt], with each of [requiredHeaders] set to exactly
 * the value given (the signature covers them), then
 * `POST /media/{mediaId}/complete`. The URL is a credential for one upload:
 * keep it out of logs and analytics.
 */
internal data class UploadIntentResponse(
    val mediaId: UUID,
    val uploadUrl: String,
    val expiresAt: Instant,
    val requiredHeaders: Map<String, String>,
) {
    override fun toString(): String = "UploadIntentResponse(mediaId=$mediaId, uploadUrl=<redacted>, expiresAt=$expiresAt)"

    companion object {
        fun from(intent: UploadIntent): UploadIntentResponse =
            UploadIntentResponse(intent.mediaId, intent.uploadUrl.toString(), intent.expiresAt, intent.requiredHeaders)
    }
}

/**
 * One of the caller's own photos or voice notes (doc 06:183: "status + signed
 * URLs for ready variants"). [status] is the asset's own lifecycle state. Once
 * a photo is `READY`, [url] is a signed GET of the largest image kept of it,
 * valid for at least 24 hours and the same all hour (ADR-0048), and [width]
 * and [height] are that image's; once a voice note is `READY`, [url] is a
 * signed GET of its Ogg Opus, the same way (ADR-0049), and [durationMs] its
 * length. Before, all are null, and each is null on the other kind.
 * `QUARANTINED` is one moderation held back from the partner (ADR-0051; the
 * entry shows it to its author as `NOT_SHARED`, Q10): its URL is still given
 * here, unless it was blocked outright. [sensitive] is true once a photo has
 * passed moderation with adult nudity found: blurred until tapped (Q16).
 * [contentType] and [sizeBytes] are what was declared for the upload, not
 * what is kept.
 *
 * @property url a bearer credential for the photo or voice note: keep it out of logs and analytics
 */
internal data class MediaResponse(
    val id: UUID,
    val kind: MediaKind,
    val status: MediaStatus,
    val contentType: String,
    val sizeBytes: Long,
    val url: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Int?,
    val sensitive: Boolean,
) {
    override fun toString(): String =
        "MediaResponse(id=$id, kind=$kind, status=$status, url=${url?.let { "<redacted>" }}, width=$width, height=$height, " +
            "durationMs=$durationMs, sensitive=$sensitive)"

    companion object {
        fun from(own: OwnAsset): MediaResponse =
            MediaResponse(
                own.asset.id,
                own.asset.kind,
                own.asset.status,
                own.asset.contentType,
                own.asset.sizeBytes,
                own.url?.toString(),
                own.width,
                own.height,
                own.durationMs,
                own.sensitive,
            )
    }
}
