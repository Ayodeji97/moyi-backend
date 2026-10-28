package com.moyi.media.service

import com.moyi.common.core.IdGenerator
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.UploadDeclaration
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What `POST /media/upload-intent` hands back (doc 06:181). The URL is a bearer credential for one upload: never logged. */
internal class UploadIntent(
    val mediaId: UUID,
    val kind: MediaKind,
    val uploadUrl: URI,
    val expiresAt: Instant,
    val requiredHeaders: Map<String, String>,
) {
    override fun toString(): String = "UploadIntent(mediaId=$mediaId, kind=$kind, uploadUrl=<redacted>, expiresAt=$expiresAt)"
}

/**
 * `POST /media/upload-intent` (doc 06:181, spec §4 D1): an asset in
 * `PENDING_UPLOAD` and a URL that accepts exactly the declared bytes, straight
 * into the storage. The API never sees the bytes (FR-044, NFR-009).
 *
 * In order: the declaration is checked (FR-042, FR-043, [UploadDeclaration]);
 * the URL is signed; only then is the asset recorded. Signing needs no network,
 * but a storage that is not configured refuses here, and then nothing is
 * written: no asset waits on a URL that was never issued.
 *
 * The asset's id and its storage key are two separate v4 UUIDs (doc 06:14), so
 * neither can be derived from the other, and the key carries nothing of the
 * owner, the bond or the kind (doc 09:18).
 *
 * The `media:intent` bucket (30 an hour, doc 06 §4) is spent before this runs,
 * by the interceptor, so a refused declaration spends a token too.
 */
@Service
internal class CreateUploadIntent(
    private val assets: MediaAssetStore,
    private val storage: ObjectStore,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    fun create(
        owner: UUID,
        kind: String?,
        contentType: String?,
        sizeBytes: Long?,
        checksum: String?,
    ): UploadIntent {
        val declaration =
            when (val outcome = UploadDeclaration.of(kind, contentType, sizeBytes, checksum)) {
                is UploadDeclaration.Outcome.Accepted -> outcome.declaration
                is UploadDeclaration.Outcome.Refused -> throw outcome.refusal.toException()
            }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val key = StorageKey.forUpload(ids.opaque())
        val expiresAt = now.plus(UPLOAD_URL_LIFETIME)
        val upload =
            try {
                storage.presignUpload(key, declaration.contentType, declaration.sizeBytes, declaration.checksumSha256, UPLOAD_URL_LIFETIME)
            } catch (unavailable: ObjectStoreUnavailableException) {
                throw MediaStorageUnavailableException(unavailable)
            }
        val asset =
            MediaAsset(
                id = ids.opaque(),
                ownerUserId = owner,
                kind = declaration.kind,
                storageKey = key,
                contentType = declaration.contentType,
                sizeBytes = declaration.sizeBytes,
                checksumSha256 = declaration.checksumSha256,
                status = MediaStatus.PENDING_UPLOAD,
                attachedEntryId = null,
                createdAt = now,
                expiresAt = expiresAt,
            )
        transactions.executeWithoutResult { assets.insert(asset) }
        return UploadIntent(asset.id, asset.kind, upload.url, expiresAt, upload.requiredHeaders)
    }

    internal companion object {
        /** Doc 05:163, spec §4 D1: an upload URL works for fifteen minutes. */
        val UPLOAD_URL_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}
