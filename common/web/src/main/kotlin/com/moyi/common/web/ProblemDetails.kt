package com.moyi.common.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Builds the RFC 9457 body doc 06 §2 specifies, on top of Spring's own
 * [ProblemDetail] — which already carries `type`, `title`, `status`,
 * `detail` and `instance`. Only `code` and `errors` are ours to add.
 *
 * **`traceId` is deliberately absent.** Doc 06 defines it as the
 * OpenTelemetry trace id, so that a user-reported problem maps to a trace.
 * There is no tracing in the application yet (doc 11, Phase 5). Emitting a
 * freshly-generated UUID in its place would satisfy the schema and be a lie:
 * a support engineer would search for it and find nothing, which is worse
 * than the field not being there. It is added by the slice that adds tracing.
 *
 * An injectable component rather than an object: it needs the configured
 * base URI, and threading that through every call site as a parameter made
 * the signature long enough that detekt objected — correctly, since the
 * base URI is a property of the builder and not of the error being built.
 *
 * Public so that a module's own `@RestControllerAdvice` can produce the same
 * body for its own exceptions — one error shape for the whole API is the
 * point, and it survives only if every producer shares the builder.
 */
@Component
class ProblemDetails(
    private val problems: ProblemProperties,
) {
    private companion object {
        /** `VALIDATION_FAILED` -> `validation-failed`, so the URI reads as documentation. */
        fun slugOf(errorCode: ErrorCode): String = errorCode.name.lowercase().replace('_', '-')

        /** `VALIDATION_FAILED` -> `Validation failed`. */
        fun titleOf(errorCode: ErrorCode): String =
            errorCode.name
                .lowercase()
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
    }

    fun of(
        status: HttpStatus,
        errorCode: ErrorCode,
        detail: String,
        instance: URI?,
        errors: List<FieldViolation>? = null,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            val baseUri = problems.baseUri
            type = baseUri.resolve("${baseUri.path.trimEnd('/')}/${slugOf(errorCode)}")
            title = titleOf(errorCode)
            this.instance = instance
            setProperty("code", errorCode.name)
            errors?.let { setProperty("errors", it) }
        }
}

/**
 * One rejected field. Note what is *not* here: the value that was rejected.
 * Bean Validation carries it on `FieldError.rejectedValue`, and copying it
 * into the response would echo the request (doc 18 §5) — which for a
 * password field would put the plaintext in the response body, the browser's
 * network tab, and any proxy log in between.
 */
data class FieldViolation(
    val field: String,
    val code: String,
    val message: String,
)
