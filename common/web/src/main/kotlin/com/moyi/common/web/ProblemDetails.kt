package com.moyi.common.web

import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Builds the RFC 9457 body doc 06 §2 specifies, on top of Spring's own
 * [ProblemDetail] — which already carries `type`, `title`, `status`,
 * `detail` and `instance`. `code`, `errors`, `traceId` and an exception's own
 * extension members are ours to add.
 *
 * **`traceId` is the id the log lines carry** (ADR-0024, amended 2026-11-08).
 * Doc 06 defines it as the OpenTelemetry trace id, so that a user-reported
 * problem maps to what the server wrote about it. It was left out until
 * tracing existed, because a freshly generated UUID in its place would have
 * been a lie: a support engineer would search for it and find nothing. Now
 * the application serves every request inside a trace (Micrometer Tracing over
 * OpenTelemetry, `app`), and the tracer puts that trace's id in the logging
 * context under [TRACE_ID_KEY], which is where every log line written for the
 * request takes it from. The body takes it from the same place, so the two
 * cannot disagree. Outside a trace (a module's own test context, which has no
 * tracer) there is no id, and the member is left out rather than invented.
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
    companion object {
        /** Micrometer Tracing's logging-context key for the current trace id: what the log lines and the body both read. */
        const val TRACE_ID_KEY = "traceId"

        /** `VALIDATION_FAILED` -> `validation-failed`, so the URI reads as documentation. */
        private fun slugOf(errorCode: ErrorCode): String = errorCode.name.lowercase().replace('_', '-')

        /** `VALIDATION_FAILED` -> `Validation failed`. */
        private fun titleOf(errorCode: ErrorCode): String =
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
            MDC.get(TRACE_ID_KEY)?.takeIf(String::isNotBlank)?.let { setProperty("traceId", it) }
        }

    /** The body of an [ApiException], its extension members included (`allowedFrom`, #142). */
    fun of(
        exception: ApiException,
        instance: URI?,
    ): ProblemDetail =
        of(exception.status, exception.errorCode, exception.detail, instance, exception.errors).apply {
            exception.extensions.forEach(::setProperty)
        }

    /**
     * The answer to a query parameter Tomcat could not decode, its one source: [UndecodableParameterFilter] gives
     * it, and `GlobalExceptionHandler` gives it too should the filter ever be passed. Its detail names no
     * parameter and quotes no value.
     */
    fun undecodableParameter(instance: URI?): ProblemDetail =
        of(
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCode.MALFORMED_REQUEST,
            detail = "A request parameter could not be read.",
            instance = instance,
        )
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
