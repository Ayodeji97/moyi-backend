package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.thymeleaf.ITemplateEngine
import org.thymeleaf.context.WebContext
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The console's error page (spec §7): HTML, never the API's RFC 9457 JSON, and never a stack
 * trace or an exception's message. Two ways in, and neither is Boot's `/error`, whose error
 * dispatch would leave the admin chain for the API's:
 *
 * - [write], from the chain's access-denied handler (a missing or wrong CSRF token is the
 *   usual 403), which runs outside Spring MVC and so renders the template itself;
 * - [AdminErrorResolver], for any failure Spring MVC raises on an admin path.
 */
@Component
@ConditionalOnAdminConsole
internal class AdminErrorPage(
    private val templates: ITemplateEngine,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
    ) {
        val exchange = JakartaServletWebApplication.buildApplication(request.servletContext).buildExchange(request, response)
        val context = WebContext(exchange, request.locale, model(status))
        response.status = status.value()
        response.contentType = MediaType.TEXT_HTML_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        templates.process(VIEW, context, response.writer)
        response.writer.flush()
    }

    companion object {
        const val VIEW = "admin/error"
        private const val UNREADABLE = "That request could not be read."
        private const val FAILED = "Something went wrong. Try again in a moment."

        /** What the page says: the status, and words chosen here, never the failure's own. */
        fun model(status: HttpStatus): Map<String, Any> =
            mapOf(
                "status" to status.value(),
                "message" to
                    when (status) {
                        HttpStatus.FORBIDDEN -> {
                            "This form has expired. Go back, reload the page and try again."
                        }

                        HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED -> {
                            "There is no page here."
                        }

                        else -> {
                            if (status.is4xxClientError) UNREADABLE else FAILED
                        }
                    },
            )
    }
}

/**
 * Every Spring MVC failure of an admin request becomes the admin error page (spec §7): an
 * exception out of an admin controller, and also the ones raised before any admin handler is
 * chosen, which a `@ControllerAdvice` scoped to this package never sees: a path with no page
 * (`NoResourceFoundException`, 404) or a method a page does not take (405). Registered first
 * among the resolvers, ahead of the API's `GlobalExceptionHandler`, by [AdminConfiguration],
 * and only for `/admin` and below; any other path is left to the resolvers after it.
 *
 * It exists only with the console on, so switched off `/admin/...` is answered as before; and a
 * refused address never reaches Spring MVC. A server failure is logged by its class alone: a
 * message can quote what was submitted, and here that is a password or a code.
 */
internal class AdminErrorResolver : HandlerExceptionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        failure: Exception,
    ): ModelAndView? {
        val path = request.requestURI.removePrefix(request.contextPath)
        val adminPath = path == ADMIN || path.startsWith("$ADMIN/")
        // Spring Security's own: ExceptionTranslationFilter must see them, to send the request
        // to sign in or to the code step (a `@PreAuthorize` refusal raises one in a handler).
        val security = failure is AccessDeniedException || failure is AuthenticationException
        if (!adminPath || security) return null
        val status = (failure as? ErrorResponse)?.let { HttpStatus.resolve(it.statusCode.value()) } ?: HttpStatus.INTERNAL_SERVER_ERROR
        if (status.is5xxServerError) log.error("An admin request failed: {}", failure.javaClass.name)
        return ModelAndView(AdminErrorPage.VIEW, AdminErrorPage.model(status), status)
    }

    private companion object {
        const val ADMIN = "/admin"
    }
}
