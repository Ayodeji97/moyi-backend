package com.moyi.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.tomcat.util.http.InvalidParameterException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Answers a request whose parameters Tomcat cannot decode — bytes that are not UTF-8 in the query string —
 * with `400 MALFORMED_REQUEST`, **before anything else reads them** (slice C6b, task 2 review I1).
 *
 * Tomcat decodes every parameter of a request the first time any one is asked for, and on bytes it cannot
 * decode it throws `InvalidParameterException`, **whose message quotes the value** as far as it could read it.
 * For a search, that value is somebody's words. Who asks first depends on log levels: below INFO the dispatcher
 * servlet reads every parameter for its request line before a handler is chosen, and the exception then escapes
 * Spring's exception handling altogether, to be logged by Tomcat at ERROR, the value quoted. So the parse is
 * forced here, outermost but for the character-encoding filter, ahead of Spring Security's chain and the
 * dispatcher servlet, and the answer does not depend on any logger's level. `GlobalExceptionHandler` answers
 * the same exception the same way, as the second guard.
 *
 * **Never carried on.** Tomcat marks the parameters parsed before it throws and keeps no record of the failure,
 * so a later `getParameter` would answer from a partial map in silence. The chain is not continued.
 *
 * **What it logs:** that a parameter could not be decoded, at WARN. Never the exception, its message, the
 * parameter's name or its value.
 *
 * **What forcing the parse changes for a request that decodes:** nothing this API answers differently. Tomcat
 * reads a body here only for a form (`application/x-www-form-urlencoded`, or multipart where the servlet is
 * configured for it). Spring rebuilds a form body from the parameters when there is no query string; with one,
 * a handler that reads the raw body is now given none. No route of this API takes a form or a raw body: each
 * reads a JSON object into a class, and a form is the same 415 to it either way (`UndecodableParameterTest`).
 *
 * **The headers Spring Security would add are written here**, because its chain is never reached: the three
 * that forbid storing the response, which `CacheHeadersTest` holds every other refusal to, and the three that
 * stop a browser sniffing, framing or "XSS-auditing" it. A copy of its defaults; `SecretsNeverLoggedTest`
 * compares this answer's headers with an ordinary refusal's, so that the copy cannot drift unseen. The rate
 * limiter's headers are absent: it is not reached, and a request answered here costs no database.
 */
@Component
@Order(UndecodableParameterFilter.ORDER)
class UndecodableParameterFilter(
    private val problems: ProblemDetails,
    private val json: JsonMapper,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val decoded =
            try {
                request.parameterMap
                true
            } catch (_: InvalidParameterException) {
                false
            }
        if (decoded) {
            filterChain.doFilter(request, response)
            return
        }
        log.warn(LOG_LINE, ErrorCode.MALFORMED_REQUEST)
        val problem = problems.undecodableParameter(URI.create(request.requestURI))
        response.status = problem.status
        NEVER_STORED.forEach { (name, value) -> response.setHeader(name, value) }
        // No charset parameter, as Spring writes it: JSON is UTF-8 (RFC 8259), and Jackson writes nothing else.
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        json.writeValue(response.outputStream, problem)
        response.outputStream.flush()
    }

    companion object {
        /**
         * Straight after Spring Boot's character-encoding filter (`HIGHEST_PRECEDENCE`), so that a form body is
         * decoded in the charset it sets; far ahead of Spring Security's chain (`-100`).
         */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 1

        /** The one WARN both guards write. Its one argument is the error code. */
        const val LOG_LINE = "{} -> 400 a request parameter could not be decoded"

        /** Spring Security's default headers, as it writes them on every other response of this API. */
        private val NEVER_STORED =
            listOf(
                HttpHeaders.CACHE_CONTROL to "no-cache, no-store, max-age=0, must-revalidate",
                HttpHeaders.PRAGMA to "no-cache",
                HttpHeaders.EXPIRES to "0",
                "X-Content-Type-Options" to "nosniff",
                "X-Frame-Options" to "DENY",
                "X-XSS-Protection" to "0",
            )
    }
}
