package com.moyi.common.security

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Rejects a token not minted for this application.
 *
 * `iss` says who signed it; `aud` says who it was signed *for*. With one key
 * and one client they look redundant, and they stop being redundant the day
 * the same issuer signs tokens for a second audience — an admin console, a
 * partner API — and a token meant for one is replayed against the other.
 * Spring Security validates `iss` and the timestamps out of the box and
 * leaves `aud` to the application, because only the application knows its
 * own name.
 */
class AudienceValidator(
    private val audience: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if (token.audience.orEmpty().contains(audience)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token was not issued for this audience", null),
            )
        }
}
