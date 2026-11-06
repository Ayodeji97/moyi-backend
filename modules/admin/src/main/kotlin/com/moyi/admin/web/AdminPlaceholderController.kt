package com.moyi.admin.web

import com.moyi.admin.service.ConditionalOnAdminConsole
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * The two pages the chain needs to be tested against before the sign-in steps exist: the
 * sign-in form (`GET` only; Ruling A) and the page behind both factors. Task 5's
 * `AdminAuthController` replaces both.
 */
@Controller
@ConditionalOnAdminConsole
@Suppress("FunctionOnlyReturningConstant") // A handler's answer is its view's name.
internal class AdminPlaceholderController {
    @GetMapping("/admin/login")
    fun login(): String = "admin/login"

    @GetMapping("/admin")
    fun home(): String = "admin/home"
}
