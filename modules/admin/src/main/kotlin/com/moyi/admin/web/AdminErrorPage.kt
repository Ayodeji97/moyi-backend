package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.common.web.ExceptionHandlerAdviceOrder
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
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
 * - [AdminErrorAdvice], for an exception thrown by an admin controller.
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

        /** What the page says: the status, and words chosen here, never the failure's own. */
        fun model(status: HttpStatus): Map<String, Any> =
            mapOf(
                "status" to status.value(),
                "message" to
                    when (status) {
                        HttpStatus.FORBIDDEN -> "This form has expired. Go back, reload the page and try again."
                        else -> "Something went wrong. Try again in a moment."
                    },
            )
    }
}

/**
 * An exception out of an admin controller becomes the admin error page, ahead of the API's
 * catch-all ([ExceptionHandlerAdviceOrder.MODULE]). Logged by its class alone: a message can
 * quote what was submitted, and here that is a password or a code.
 */
@ControllerAdvice(basePackageClasses = [AdminErrorAdvice::class])
@Order(ExceptionHandlerAdviceOrder.MODULE)
@ConditionalOnAdminConsole
internal class AdminErrorAdvice {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun failed(failure: Exception): ModelAndView {
        log.error("An admin request failed: {}", failure.javaClass.name)
        return ModelAndView(AdminErrorPage.VIEW, AdminErrorPage.model(HttpStatus.INTERNAL_SERVER_ERROR), HttpStatus.INTERNAL_SERVER_ERROR)
    }
}
