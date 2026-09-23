package com.moyi.common.security

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A context just large enough to put real HTTP through the real filter
 * chain: `common:core` (clock, ids), `common:web` (the problem builder and
 * catch-all advice) and this module. No database — the revocation port is
 * answered by [InMemoryTokenRevocations], which is exactly the seam the port
 * exists to provide.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common"])
@ConfigurationPropertiesScan("com.moyi.common")
class SecurityTestApplication {
    @Bean
    fun tokenRevocations(): InMemoryTokenRevocations = InMemoryTokenRevocations()
}

/** Every user exists unless [remove]d; nobody is revoked unless [revokeBefore] says so. */
class InMemoryTokenRevocations : TokenRevocations {
    private val invalidBefore = ConcurrentHashMap<UUID, Instant>()
    private val missing = ConcurrentHashMap.newKeySet<UUID>()

    override fun forUser(userId: UUID): TokenRevocation? = if (userId in missing) null else TokenRevocation(invalidBefore[userId])

    fun revokeBefore(
        userId: UUID,
        instant: Instant,
    ) {
        invalidBefore[userId] = instant
    }

    fun remove(userId: UUID) {
        missing.add(userId)
    }

    fun reset() {
        invalidBefore.clear()
        missing.clear()
    }
}

/** Two endpoints: one any signed-in user may call, one that needs a scope no token carries. */
@RestController
class ProbeController {
    @GetMapping("/api/v1/probe/whoami")
    fun whoami(caller: CurrentUser): Map<String, String> = mapOf("userId" to caller.id.toString())

    @GetMapping("/api/v1/probe/admin")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    fun admin(): Map<String, String> = mapOf("secret" to "plans")
}

/** Present so the chain test can prove a `CurrentUser` on a public route is a 401, not a 500. */
@Component
class ProbeMarker
