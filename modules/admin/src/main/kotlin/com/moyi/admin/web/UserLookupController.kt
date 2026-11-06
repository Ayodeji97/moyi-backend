package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.LookupResult
import com.moyi.admin.service.UserLookup
import com.moyi.common.security.ClientAddressResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/** `GET /admin/users?q=`: the lookup page (spec §9). An empty `q` shows the form and looks nothing up. */
@Controller
@ConditionalOnAdminConsole
internal class UserLookupController(
    private val lookup: UserLookup,
    private val addresses: ClientAddressResolver,
) {
    @GetMapping("/admin/users")
    fun users(
        @RequestParam(defaultValue = "") q: String,
        authentication: Authentication,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (q.isNotBlank()) {
            val result = lookup.find(q, authentication.principal as UUID, addresses.orNull(request))
            when (result) {
                is LookupResult.Found -> {
                    model.addAttribute("user", result.user)
                    model.addAttribute("bonds", result.bonds)
                }

                LookupResult.NotFound -> {
                    model.addAttribute("notFound", true)
                }
            }
        }
        return "admin/users"
    }
}
