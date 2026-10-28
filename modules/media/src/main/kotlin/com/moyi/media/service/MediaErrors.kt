package com.moyi.media.service

import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.FieldViolation
import com.moyi.common.web.NotFoundException
import com.moyi.media.domain.UploadDeclaration
import org.springframework.http.HttpStatus

// The refusals the media routes give. Each extends ApiException, so the shared
// handler writes the RFC 9457 body and this module needs no advice of its own.

/**
 * One answer for an asset that is somebody else's, deleted, never existed, or
 * named by something that is not a UUID (doc 06:184, CLAUDE.md: never 403).
 * A partner reaches media only through an entry (BR-1), never by its id.
 */
internal class MediaNotFoundException : NotFoundException("That photo or voice note was not found.")

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
