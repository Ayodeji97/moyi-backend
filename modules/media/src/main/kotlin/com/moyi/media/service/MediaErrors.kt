package com.moyi.media.service

import com.moyi.common.security.VerifiedEmails
import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.FieldViolation
import com.moyi.common.web.NotFoundException
import com.moyi.media.domain.UploadDeclaration
import org.springframework.http.HttpStatus
import java.util.UUID

// The refusals the media routes give. Each extends ApiException, so the shared
// handler writes the RFC 9457 body and this module needs no advice of its own.

/**
 * One answer for an asset that is somebody else's, deleted, never existed, or
 * named by something that is not a UUID (doc 06:184, CLAUDE.md: never 403).
 * A partner reaches media only through an entry (BR-1), never by its id.
 */
internal class MediaNotFoundException : NotFoundException("That photo or voice note was not found.")

/**
 * The owner's ruling, 2026-10-29 (ADR-0049): photos and voice notes are for an
 * account that has confirmed its address. Bond's FR-002 refusal, word for word:
 * the same status, code and sentence, so the client's move is the one it
 * already has (`states.md` §1's screen 3). A fact about the caller's own
 * account, so naming it discloses nothing; an id that names nobody is the same
 * answer, as it is in bond.
 */
internal class EmailNotVerifiedException :
    ApiException(
        HttpStatus.FORBIDDEN,
        ErrorCode.EMAIL_NOT_VERIFIED,
        "Verify your email address first.",
    )

/**
 * The ruling's one check, for the three ways media is made or used: an upload
 * intent, its completion, and a claim by an entry. Each runs it first, before
 * anything is read or signed: an unverified caller pays for nothing further.
 */
internal fun VerifiedEmails.requireVerified(owner: UUID) {
    if (!isVerified(owner)) throw EmailNotVerifiedException()
}

internal class MediaDeclarationInvalidException(
    violations: List<UploadDeclaration.Violation>,
) : ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ErrorCode.VALIDATION_FAILED,
        "One or more fields are not valid.",
        errors = violations.map { FieldViolation(it.field, it.code, it.message) },
    )

internal class MediaTypeUnsupportedException :
    ApiException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ErrorCode.MEDIA_TYPE_UNSUPPORTED,
        "That kind of file cannot be shared.",
    )

internal class MediaQuotaExceededException(
    maxBytes: Long,
) : ApiException(
        HttpStatus.CONTENT_TOO_LARGE,
        ErrorCode.MEDIA_QUOTA_EXCEEDED,
        "That file is too large. The limit is ${maxBytes / BYTES_PER_MEBIBYTE} MB.",
    )

internal class MediaNotUploadedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.MEDIA_NOT_UPLOADED,
        "The upload has not arrived yet. Finish sending the file, then try again.",
    )

internal class MediaStorageUnavailableException(
    cause: Throwable,
) : ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        ErrorCode.MEDIA_STORAGE_UNAVAILABLE,
        "Photos and voice notes cannot be saved right now. Please try again later.",
        cause,
    )

/** `DELETE /media/{id}` of an asset attached to an entry (doc 06:184: "only if unattached"). */
internal class MediaAttachedException :
    ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.MEDIA_ATTACHED,
        "This photo or voice note is part of an entry, and stays with it.",
    )

internal class MediaBusyException(
    cause: Throwable,
) : ApiException(
        HttpStatus.CONFLICT,
        ErrorCode.MEDIA_BUSY,
        "This photo or voice note is being changed. Please try again.",
        cause,
    )

internal fun UploadDeclaration.Refusal.toException(): ApiException =
    when (this) {
        is UploadDeclaration.Refusal.Invalid -> MediaDeclarationInvalidException(violations)
        UploadDeclaration.Refusal.UnsupportedType -> MediaTypeUnsupportedException()
        is UploadDeclaration.Refusal.TooLarge -> MediaQuotaExceededException(maxBytes)
    }

private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
