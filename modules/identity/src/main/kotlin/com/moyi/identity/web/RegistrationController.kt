package com.moyi.identity.web

import com.moyi.common.security.ClientContext
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.identity.service.RegisterUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/auth/register` (doc 06 §3.1, FR-001, FR-011).
 *
 * **201 with no body, always** — and the empty body is the security control,
 * not an oversight. ADR-0015 requires the response to be identical whether or
 * not the address is already registered, and *any* user-specific content — an
 * id, a status, a created timestamp — is something the duplicate path cannot
 * produce and therefore something an attacker can distinguish. The only body
 * that can be identical in both cases is no body at all.
 *
 * Nothing here does any work (doc 18 §4): it maps HTTP to the service and
 * back. The mapping itself lives on [RegisterRequest], so that this class has
 * no logic to test separately from the endpoint. The [ClientContext] is the
 * caller's address and user agent, already hashed, for the consent rows
 * (FR-011, doc 07 §2); the controller never sees the address itself.
 */
@RestController
@RequestMapping("/api/v1/auth")
internal class RegistrationController(
    private val registerUser: RegisterUser,
) {
    /** FR-012: three an hour per address. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(RateLimitBucket.AUTH_REGISTER_IP)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        client: ClientContext,
    ) {
        registerUser.register(request.toCommand(client))
    }
}
