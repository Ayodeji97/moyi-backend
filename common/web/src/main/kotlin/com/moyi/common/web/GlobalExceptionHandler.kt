package com.moyi.common.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/**
 * Turns every failure into the RFC 9457 body doc 06 §2 specifies.
 *
 * Extends [ResponseEntityExceptionHandler] so the exceptions Spring MVC
 * raises on its own — unsupported method, unreadable media type, missing
 * parameter — arrive in the same shape as ours, rather than in whatever
 * Boot's default error page produces. A client that has to parse two error
 * formats will parse one of them badly.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val problems: ProblemDetails,
) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A well-formed body whose contents are not acceptable.
     *
     * **422, not 400** — doc 06 §2's table reserves 400 for a *malformed*
     * request and 422 for one that is semantically invalid. A password of
     * the wrong length is perfectly well-formed JSON. This is a deliberate
     * departure from the more common "400 for everything" convention, and
     * the reason it is worth making is that the two cases need different
     * client behaviour: 400 means the client built the request wrongly and
     * retrying is pointless, 422 means the *user* needs to change something.
     */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val violations =
            ex.bindingResult.fieldErrors.map { error ->
                FieldViolation(
                    field = error.field,
                    // The constraint's own name — `Size`, `NotBlank`, `AssertTrue` —
                    // uppercased. Derived rather than invented per field, so two
                    // endpoints cannot disagree about what to call the same failure.
                    code = (error.code ?: "INVALID").screamingSnakeCase(),
                    message = error.defaultMessage ?: "Invalid value.",
                )
            }

        return respond(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            errorCode = ErrorCode.VALIDATION_FAILED,
            detail = "One or more fields are not valid.",
            request = request,
            errors = violations,
        )
    }

    /** Unparseable body: not JSON, wrong type for a field, a missing required property. */
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> =
        respond(
            status = HttpStatus.BAD_REQUEST,
            errorCode = ErrorCode.MALFORMED_REQUEST,
            // Jackson's own message names classes and sometimes quotes the
            // offending input; neither belongs in a response (doc 18 §9).
            detail = "The request body could not be read.",
            request = request,
        )

    @ExceptionHandler(ApiException::class)
    fun handleApiException(
        ex: ApiException,
        request: WebRequest,
    ): ResponseEntity<Any> {
        // At WARN, not ERROR: these are contract outcomes, and a dashboard
        // that alerts on a user typing a short password is a dashboard
        // people learn to ignore.
        log.warn("{} -> {} {}", ex.errorCode, ex.status.value(), ex.detail)
        return respond(ex.status, ex.errorCode, ex.detail, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: WebRequest,
    ): ResponseEntity<Any> {
        // The stack trace goes to the log, where operators can see it. The
        // client gets a sentence (doc 18 §9: never return an internal
        // exception message).
        log.error("Unhandled exception", ex)
        return respond(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            errorCode = ErrorCode.INTERNAL_ERROR,
            detail = "Something went wrong. Please try again.",
            request = request,
        )
    }

    /**
     * Stamps a [ErrorCode] onto the problem details Spring builds for itself.
     *
     * Every exception `ResponseEntityExceptionHandler` handles without our
     * help — unsupported method, unreadable media type, no such route —
     * already produced a correctly shaped `application/problem+json` body,
     * and every one of them was missing `code`. Doc 06 §2 makes `code` the
     * stable contract a client switches on, "enumerated and exhaustive,
     * generated into the client as a sealed class". A third of the responses
     * arriving without one does not break that design loudly; it breaks it by
     * making the client's exhaustive `when` meet a value it cannot name.
     *
     * Found by asking for a 404, a 405 and a 415 against the running
     * application rather than by reading the handler, which looked complete.
     */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val response = super.handleExceptionInternal(ex, body, headers, statusCode, request)
        val problem = response?.body as? ProblemDetail ?: return response

        // Only when absent: our own handlers set it deliberately, and a
        // status-derived guess must never overwrite a specific code.
        if (problem.properties?.containsKey("code") != true) {
            problem.setProperty("code", codeFor(statusCode).name)
        }
        return response
    }

    private fun codeFor(status: HttpStatusCode): ErrorCode =
        when (status.value()) {
            HttpStatus.NOT_FOUND.value() -> {
                ErrorCode.NOT_FOUND
            }

            HttpStatus.METHOD_NOT_ALLOWED.value() -> {
                ErrorCode.METHOD_NOT_ALLOWED
            }

            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), HttpStatus.NOT_ACCEPTABLE.value() -> {
                ErrorCode.UNSUPPORTED_MEDIA_TYPE
            }

            // A 4xx Spring raised that we have not named is still the client
            // sending something this application cannot read.
            in CLIENT_ERROR_RANGE -> {
                ErrorCode.MALFORMED_REQUEST
            }

            else -> {
                ErrorCode.INTERNAL_ERROR
            }
        }

    private fun respond(
        status: HttpStatus,
        errorCode: ErrorCode,
        detail: String,
        request: WebRequest,
        errors: List<FieldViolation>? = null,
    ): ResponseEntity<Any> =
        ResponseEntity
            .status(status)
            .body(
                problems.of(
                    status = status,
                    errorCode = errorCode,
                    detail = detail,
                    instance = request.instanceUri(),
                    errors = errors,
                ),
            )

    private fun WebRequest.instanceUri(): URI? = (this as? ServletWebRequest)?.request?.requestURI?.let(URI::create)

    private fun String.screamingSnakeCase(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    private companion object {
        val CLIENT_ERROR_RANGE = 400..499
    }
}
