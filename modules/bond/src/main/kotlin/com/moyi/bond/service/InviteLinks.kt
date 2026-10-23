package com.moyi.bond.service

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.config.InviteProperties
import org.springframework.stereotype.Component
import java.net.URI

/**
 * The link half of FR-022 — `<link-base-url>/7KQ4MZ`, the base being
 * `moyi.bond.invite.link-base-url` (required; ADR-0018's amendment of 2026-10-22).
 *
 * One place that builds it, so the code and the link cannot disagree, and so
 * the base URL is read from configuration rather than assembled at each call
 * site. The trailing-slash trim is why this is a class and not a string
 * template: a base URL configured with or without one must produce the same
 * link.
 */
@Component
internal class InviteLinks(
    private val properties: InviteProperties,
) {
    fun linkFor(code: InviteCode): URI = URI.create("${properties.linkBaseUrl.toString().trimEnd('/')}/${code.value}")
}
