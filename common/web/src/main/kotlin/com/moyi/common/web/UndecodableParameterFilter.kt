package com.moyi.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.tomcat.util.http.InvalidParameterException
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Answers a request whose parameters Tomcat cannot decode — bytes that are not UTF-8 in the query string —
 * with `400 MALFORMED_REQUEST`, **before the dispatcher servlet or anything after it reads them** (slice C6b,
 * task 2 review I1).
 *
 * Tomcat decodes every parameter of a request the first time any one is asked for, and on bytes it cannot
 * decode it throws `InvalidParameterException`, **whose message quotes the value** as far as it could read it.
 * For a search, that value is somebody's words. Who asks first depends on log levels: below INFO the dispatcher
 * servlet reads every parameter for its request line before a handler is chosen, and the exception then escapes
 * Spring's exception handling altogether, to be logged by Tomcat at ERROR, the value quoted. So the parse is
 * forced here, and the answer does not depend on any logger's level. `GlobalExceptionHandler` answers the same
 * exception the same way, as the second guard.
 *
 * **Where it runs: straight after Spring Security's chain** ([ORDER]). Seen at run time, in Tomcat's filter
 * maps: characterEncodingFilter (`HIGHEST_PRECEDENCE`), webMvcObservationFilter (`HIGHEST_PRECEDENCE + 1`),
 * formContentFilter (-9900), requestContextFilter (-105), springSecurityFilterChain (-100), this (-99), then the
 * unordered idempotencyRequestCachingFilter. Nothing in Security's chain reads a parameter (every level at TRACE,
 * `SecretsNeverLoggedTest`), so running after it costs nothing, and it buys three things (task 2 re-review m1):
 * a caller who is not signed in is told 401 first, as by every other refusal; no body is parsed before the
 * caller is known; and Security's own header writer gives this answer its headers, `Cache-Control` and the
 * rest, so they are not copied here. `SecretsNeverLoggedTest` compares them with an ordinary refusal's, and
 * holds the 401.
 *
 * **Never carried on.** Tomcat marks the parameters parsed before it throws and keeps no record of the failure,
 * so a later `getParameter` would answer from a partial map in silence. The chain is not continued.
 *
 * **What it logs:** that a parameter could not be decoded, at WARN. Never the exception, its message, the
 * parameter's name or its value.
 *
 * **What forcing the parse changes for a request that decodes:** nothing this API answers differently. Tomcat
 * reads a body here only for a form (`application/x-www-form-urlencoded`; multipart is switched off in
 * `application.yml`). Spring rebuilds a form body from the parameters when there is no query string; with one,
 * a handler that reads the raw body is now given none. No route of this API takes a form or a raw body: each
 * reads a JSON object into a class, and a form is the same 415 to it either way (`UndecodableParameterTest`).
 * The rate limiter, an interceptor, is not reached: a request answered here costs no database.
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
        // No charset parameter, as Spring writes it: JSON is UTF-8 (RFC 8259), and Jackson writes nothing else.
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        json.writeValue(response.outputStream, problem)
        response.outputStream.flush()
    }

    companion object {
        /**
         * Spring Boot's `SecurityProperties.DEFAULT_FILTER_ORDER` (-100), which `common:web` cannot import: it
         * does not depend on Spring Security. Should this filter ever run ahead of that chain, a caller who is
         * not signed in would be told 400 rather than 401, and `SecretsNeverLoggedTest`'s 401 probes fail.
         */
        const val SPRING_SECURITY_ORDER = -100

        /** Straight after Spring Security's chain. */
        const val ORDER = SPRING_SECURITY_ORDER + 1

        /** The one WARN both guards write. Its one argument is the error code. */
        const val LOG_LINE = "{} -> 400 a request parameter could not be decoded"
    }
}
