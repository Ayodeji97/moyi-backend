package com.moyi.admin.web

import com.moyi.admin.service.AdminProperties
import com.moyi.admin.service.ConditionalOnAdminConsole
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The console's root (spec §4): absent unless `moyi.admin.enabled=true` in a web application,
 * and present, it refuses to start without an allow-list and a valid TOTP key.
 *
 * It also serves the vendored assets. They live in `classpath:/admin-console/`, not under
 * Boot's default `static/`, because a file there is served whether the console is on or not:
 * switched off, `/admin/static/htmx.min.js` would answer a token holder `200` where any other
 * unknown path answers `404`, and say the console is installed (`AdminDisabledTest`).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminConsole
@EnableConfigurationProperties(AdminProperties::class)
@Import(AdminSecurityConfiguration::class)
internal class AdminConfiguration(
    properties: AdminProperties,
) : WebMvcConfigurer {
    init {
        properties.validate()
    }

    /** First, so that an admin path's failure is the admin page and never the API's JSON. */
    override fun extendHandlerExceptionResolvers(resolvers: MutableList<HandlerExceptionResolver>) {
        resolvers.add(0, AdminErrorResolver())
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/admin/static/**").addResourceLocations("classpath:/admin-console/")
    }
}
