package com.moyi.media.web

import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.service.UploadIntent
import java.time.Instant
import java.util.UUID

/**
 * `POST /media/upload-intent`'s body (doc 06:181). Every field is nullable here
 * and checked by the domain's `UploadDeclaration`, the one place FR-042 and
 * FR-043 are written: a missing field and a wrong one are both `422` naming the
 * field, never `400`.
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
 * One of the caller's own photos or voice notes (doc 06:183). Until processing
 * exists (D2, D3) an asset is `PENDING_UPLOAD` or `UPLOADED`, and carries no
 * URL; D2 adds the signed URLs of its ready variants.
 */
internal data class MediaResponse(
    val id: UUID,
    val kind: MediaKind,
    val status: MediaStatus,
    val contentType: String,
    val sizeBytes: Long,
) {
    companion object {
        fun from(asset: MediaAsset): MediaResponse = MediaResponse(asset.id, asset.kind, asset.status, asset.contentType, asset.sizeBytes)
    }
}
