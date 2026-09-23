package com.moyi.common.security

import com.moyi.common.web.ErrorCode
import com.moyi.common.web.ExceptionHandlerAdviceOrder
import com.moyi.common.web.ProblemDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * 401 in the RFC 9457 shape doc 06 §2 requires of every error.
 *
 * Spring Security's default for a resource server is a bare 401 with a
 * `WWW-Authenticate` header and **no body**. Doc 06 makes `code` the stable
 * contract the client switches on, "enumerated and exhaustive"; a 401 with
 * no code is a value the client's exhaustive `when` cannot name, arriving on
 * the single most common error path in a mobile app. So the body is written
 * here, through the same [ProblemDetails] builder everything else uses.
 *
 * **The header stays.** RFC 6750 §3: the challenge is `Bearer`, with
 * `error="invalid_token"` when a token was presented and refused. It is what
 * a generic HTTP client keys on, and removing it to save a line would make
 * this the one 401 on the internet that does not say how to authenticate.
 *
 * **What is logged.** The RFC 6750 error code and description — "expired",
 * "issuer is not valid" — at WARN. Never the token, and the description is
 * Spring's own text about a claim, not the claim's value.
 */
@Component
class ProblemAuthenticationEntryPoint(
    private val problems: ProblemDetails,
    private val json: JsonMapper,
) : AuthenticationEntryPoint {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val tokenWasPresented = authException is InvalidBearerTokenException
        if (tokenWasPresented) {
            log.warn("Bearer token refused: {}", authException.message)
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        }
        response.writeProblem(
            json,
            problems.of(
                status = HttpStatus.UNAUTHORIZED,
                errorCode = ErrorCode.UNAUTHENTICATED,
                detail = if (tokenWasPresented) "Your session is not valid. Sign in again." else "Sign in to continue.",
                instance = URI.create(request.requestURI),
            ),
        )
    }
}

/** 403 in the same shape. Reached when a valid token lacks an authority a `@PreAuthorize` or URL rule demands. */
@Component
class ProblemAccessDeniedHandler(
    private val problems: ProblemDetails,
    private val json: JsonMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.writeProblem(json, forbidden(problems, URI.create(request.requestURI)))
    }
}

/**
 * The same 403 for an [AccessDeniedException] raised *inside* a controller by
 * method security.
 *
 * The filter-level handler above never sees that one: `@PreAuthorize` fails
 * after the `DispatcherServlet` has taken the request, so the exception is
 * caught by MVC's advice chain — where `common:web`'s catch-all would turn
 * a denied request into a **500**, complete with a stack trace in the log.
 * Ordered ahead of the catch-all for the same reason every module advice is
 * (see `IdentityExceptionHandler`).
 */
@Order(ExceptionHandlerAdviceOrder.MODULE)
@RestControllerAdvice
class SecurityExceptionHandler(
    private val problems: ProblemDetails,
) {
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(request: WebRequest): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(forbidden(problems, (request as? ServletWebRequest)?.request?.requestURI?.let(URI::create)))
}

private fun forbidden(
    problems: ProblemDetails,
    instance: URI?,
): ProblemDetail =
    problems.of(
        status = HttpStatus.FORBIDDEN,
        errorCode = ErrorCode.FORBIDDEN,
        detail = "You do not have permission to do that.",
        instance = instance,
    )

private fun HttpServletResponse.writeProblem(
    json: JsonMapper,
    problem: ProblemDetail,
) {
    status = problem.status
    contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    characterEncoding = Charsets.UTF_8.name()
    json.writeValue(outputStream, problem)
    outputStream.flush()
}
