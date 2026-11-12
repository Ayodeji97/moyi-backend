package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.ModerationConsole
import com.moyi.admin.service.ReviewOrigin
import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView

/**
 * `GET /admin/media/{mediaId}/review` (A2 spec §5.3, §7.4): the only A2 route that serves member
 * content. The console decides the look under its locks, writes `MEDIA_VIEWED` and commits before
 * this sends a byte; a refused look, an id that is not a UUID and an origin that cannot be read
 * are all the same `404` page.
 *
 * `via` and `report` name the page the link was on (spec §9.2), as the report and quarantine
 * pages build it. The console believes a report's only when that report holds the asset.
 *
 * Headers: the served variant's own content type, `inline`, `nosniff` (the chain's default
 * headers) and `Cross-Origin-Resource-Policy: same-origin`. `Cache-Control: no-store` is the
 * chain's, written on every admin response as it commits, over anything a handler set; this
 * route has no second copy of it to drift.
 */
@Controller
@ConditionalOnAdminConsole
@PreAuthorize("hasRole('MODERATOR')")
internal class MediaReviewController(
    private val console: ModerationConsole,
    private val addresses: ClientAddressResolver,
) {
    @GetMapping("/admin/media/{mediaId}/review")
    @Suppress("LongParameterList") // The asset, the page it came from, and the request it answers.
    fun review(
        @PathVariable mediaId: String,
        @RequestParam(defaultValue = "") via: String,
        @RequestParam(required = false) report: String?,
        authentication: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ModelAndView? {
        val id = ModerationPages.uuidOrNull(mediaId)
        val origin =
            when (via) {
                QUARANTINE -> ReviewOrigin.Quarantine
                REPORT -> ModerationPages.uuidOrNull(report)?.let(ReviewOrigin::Report)
                else -> null
            }
        val copy =
            if (id != null && origin != null) {
                console.openForReview(id, origin, ModerationPages.adminId(authentication), addresses.orNull(request))
            } else {
                null
            }
        if (copy == null) return ModerationPages.notFound()
        // Committed already, with its audit row: from here the copy is only sent, and closed.
        copy.use {
            response.contentType = it.contentType
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline")
            response.setHeader(CORP, "same-origin")
            it.content.transferTo(response.outputStream)
            response.flushBuffer()
        }
        return null
    }

    private companion object {
        const val QUARANTINE = "QUARANTINE"
        const val REPORT = "REPORT"
        const val CORP = "Cross-Origin-Resource-Policy"
    }
}
