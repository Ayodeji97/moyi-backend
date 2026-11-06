package com.moyi.admin.web

import org.springframework.security.core.authority.FactorGrantedAuthority

/**
 * The authorities an admin session carries (spec §6), and the session attributes the sign-in
 * steps write and [AdminSessionGuardFilter] reads.
 *
 * The principal of an admin `Authentication` is the admin's id, a `UUID`. The password step
 * stores one holding only [FACTOR_PASSWORD]; the TOTP step replaces it with one holding both
 * factors and the role. Spring Security 7.1 has no TOTP factor (its `FactorGrantedAuthority`
 * names PASSWORD, OTT, WEBAUTHN and others; OTT is a magic link), so [FACTOR_TOTP] is this
 * console's own, granted only by the TOTP step.
 */
internal object AdminAuthorities {
    const val FACTOR_PASSWORD = FactorGrantedAuthority.PASSWORD_AUTHORITY
    const val FACTOR_TOTP = "FACTOR_TOTP"

    /** The account's `credentials_changed_at` as the password step read it, an `Instant`. */
    const val CREDENTIALS_CHANGED_AT = "admin.credentialsChangedAt"

    /** The password step's time plus the absolute lifetime, an `Instant`. */
    const val ABSOLUTE_DEADLINE = "admin.absoluteDeadline"
}
