package com.moyi.admin.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication

/**
 * The console's one switch (spec §4): `moyi.admin.enabled=true`, in a web application. Every
 * admin bean that Spring would otherwise find by scanning carries it, because the app scans
 * all of `com.moyi`, so a condition on the root `AdminConfiguration` alone would leave the
 * beans it imports standing on their own. The web condition keeps the media worker
 * (`web-application-type: none`) from ever serving it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["moyi.admin.enabled"], havingValue = "true")
@ConditionalOnWebApplication
internal annotation class ConditionalOnAdminConsole
