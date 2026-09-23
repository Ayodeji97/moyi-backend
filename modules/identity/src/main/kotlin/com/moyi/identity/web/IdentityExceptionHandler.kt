package com.moyi.identity.web

import com.moyi.common.web.ErrorCode
import com.moyi.common.web.ExceptionHandlerAdviceOrder
import com.moyi.common.web.ProblemDetails
import com.moyi.identity.domain.HashingCapacityExceededException
import com.moyi.identity.domain.PasswordResetTokenExpiredException
import com.moyi.identity.domain.PasswordResetTokenInvalidException
import com.moyi.identity.domain.RefreshTokenInvalidException
import com.moyi.identity.domain.RefreshTokenReusedException
import com.moyi.identity.domain.VerificationTokenExpiredException
import com.moyi.identity.domain.VerificationTokenInvalidException
import com.moyi.identity.service.AuthenticatedUserMissingException
import com.moyi.identity.service.InvalidCredentialsException
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import java.net.URI

/**
 * Identity's own failures, translated into the shared error shape.
 *
 * This exists so the `domain` layer can throw without knowing what a status
 * code is. [HashingCapacityExceededException] describes a fact about this
 * system's capacity; that it is a 503 with a `Retry-After` is a fact about
 * HTTP, and HTTP lives here.
 *
 * The `@Order` is load-bearing. `common:web`'s catch-all declares
 * `@ExceptionHandler(Exception::class)`, and an advice without an explicit
 * order sorts at `LOWEST_PRECEDENCE` — the same place the catch-all sorts —
 * so without this the two tie and the winner is whichever bean Spring found
 * first. `ExceptionHandlerAdviceOrderTest` pins it.
 */
@Order(ExceptionHandlerAdviceOrder.MODULE)
@RestControllerAdvice(basePackages = ["com.moyi.identity"])
internal class IdentityExceptionHandler(
    private val problems: ProblemDetails,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * FR-002. 422 and not 404: the *route* exists and the body parsed; what is
     * wrong is the value, which is doc 06 §2's definition of 422. The detail
     * does not echo the token — it is a credential, even a wrong one.
     */
    @ExceptionHandler(VerificationTokenInvalidException::class)
    fun handleVerificationTokenInvalid(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            errorCode = ErrorCode.VERIFICATION_TOKEN_INVALID,
            detail = "That verification link is not recognised.",
            request = request,
        )

    /**
     * 410 Gone, which doc 06 §2 already uses for a spent invite: the resource
     * existed and will not again. The sentence is `states.md` §1's copy for
     * the "Link expired" state, so the client can show it as-is or switch on
     * the code.
     */
    @ExceptionHandler(VerificationTokenExpiredException::class)
    fun handleVerificationTokenExpired(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.GONE,
            errorCode = ErrorCode.VERIFICATION_TOKEN_EXPIRED,
            detail = "That link has expired. Links last 24 hours and work once. We can send you a new one.",
            request = request,
        )

    @ExceptionHandler(PasswordResetTokenInvalidException::class)
    fun handlePasswordResetTokenInvalid(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            errorCode = ErrorCode.PASSWORD_RESET_TOKEN_INVALID,
            detail = "That password reset link is not recognised.",
            request = request,
        )

    @ExceptionHandler(PasswordResetTokenExpiredException::class)
    fun handlePasswordResetTokenExpired(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.GONE,
            errorCode = ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED,
            detail = "That password reset link has expired or was already used.",
            request = request,
        )

    /**
     * A valid token whose user vanished between the filter and the handler.
     * 401 `UNAUTHENTICATED`, the same code the filter chain gives a token for
     * a user that never existed: from the client's side those are one case.
     */
    @ExceptionHandler(AuthenticatedUserMissingException::class)
    fun handleAuthenticatedUserMissing(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.UNAUTHORIZED,
            errorCode = ErrorCode.UNAUTHENTICATED,
            detail = "Your session is not valid. Sign in again.",
            request = request,
        )

    /**
     * FR-003 / doc 06 §2's security rule: one 401 for every refusal, and no
     * `WWW-Authenticate`, because no bearer credential was presented — this is
     * a login form, not a resource request. The sentence is the app's fallback
     * copy for the sign-in error; `copy.md` has no line for it yet, so this
     * one follows its rules: two short sentences, no dash, no blame.
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.UNAUTHORIZED,
            errorCode = ErrorCode.INVALID_CREDENTIALS,
            detail = "That email and password do not match. Check both and try again.",
            request = request,
        )

    /** The client's only correct move is to sign in again. */
    @ExceptionHandler(RefreshTokenInvalidException::class)
    fun handleInvalidRefreshToken(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.UNAUTHORIZED,
            errorCode = ErrorCode.REFRESH_TOKEN_INVALID,
            detail = "This session has ended. Sign in again to continue.",
            request = request,
        )

    /**
     * Doc 06 §3.1 names this code; doc 13 has the client wipe its stored
     * credentials and explain why on seeing it. The family is already revoked
     * by the time this is written.
     */
    @ExceptionHandler(RefreshTokenReusedException::class)
    fun handleRefreshTokenReused(request: WebRequest): ResponseEntity<Any> =
        problem(
            status = HttpStatus.UNAUTHORIZED,
            errorCode = ErrorCode.TOKEN_REUSE_DETECTED,
            detail = "This sign-in was used from two places at once, so every device has been signed out. Sign in again to continue.",
            request = request,
        )

    /**
     * NFR-005a: saturation is a 503 with a `Retry-After`, not a 500.
     *
     * The distinction is the whole value of the response. A 500 tells a client
     * that something is broken and retrying is probably pointless; a 503 with
     * one second tells it the truth — the server is briefly full, the request
     * was fine, try again shortly. Without `Retry-After` the client picks its
     * own interval, and every client picking the same one is how a brief
     * saturation becomes a sustained one.
     *
     * Takes no exception parameter: this one carries nothing the response
     * needs, and an unused parameter is a promise that something reads it.
     */
    @ExceptionHandler(HashingCapacityExceededException::class)
    fun handleHashingCapacity(request: WebRequest): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .body(
                problems.of(
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    errorCode = ErrorCode.HASHING_CAPACITY_EXCEEDED,
                    detail = "We are briefly at capacity. Please try again in a moment.",
                    instance = (request as? ServletWebRequest)?.request?.requestURI?.let(URI::create),
                ),
            )

    private fun problem(
        status: HttpStatus,
        errorCode: ErrorCode,
        detail: String,
        request: WebRequest,
    ): ResponseEntity<Any> {
        // WARN, as the catch-all does for contract outcomes: a rate of these is
        // worth a graph, a single one is not worth a page.
        log.warn("{} -> {}", errorCode, status.value())
        return ResponseEntity
            .status(status)
            .body(
                problems.of(
                    status = status,
                    errorCode = errorCode,
                    detail = detail,
                    instance = (request as? ServletWebRequest)?.request?.requestURI?.let(URI::create),
                ),
            )
    }

    private companion object {
        /** A hash takes ~150 ms, so a second is long enough for the queue to drain and short enough to feel instant. */
        const val RETRY_AFTER_SECONDS = "1"
    }
}
