package com.moyi.identity.web

import com.moyi.common.web.ErrorCode
import com.moyi.common.web.ExceptionHandlerAdviceOrder
import com.moyi.common.web.ProblemDetails
import com.moyi.identity.domain.HashingCapacityExceededException
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

    private companion object {
        /** A hash takes ~150 ms, so a second is long enough for the queue to drain and short enough to feel instant. */
        const val RETRY_AFTER_SECONDS = "1"
    }
}
