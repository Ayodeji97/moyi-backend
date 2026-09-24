package com.moyi.common.security

import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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

/**
 * Six endpoints: one any signed-in user may call, one that needs a scope no
 * token carries, two on the *public* auth paths the chain permits —
 * `identity`'s controllers are not on this classpath, so the paths are free
 * — one rate-limited per address, one that reports who is calling, and two
 * that exercise the per-user and multi-bucket forms of [RateLimited].
 */
@RestController
class ProbeController {
    @GetMapping("/api/v1/probe/whoami")
    fun whoami(caller: CurrentUser): Map<String, String> = mapOf("userId" to caller.id.toString())

    @PostMapping("/api/v1/auth/register")
    @RateLimited(RateLimitBucket.AUTH_REGISTER_IP)
    fun limited(): Map<String, String> = mapOf("ok" to "true")

    /** Serialises the whole context, so the test can assert the address itself is *not* in it. */
    @PostMapping("/api/v1/auth/login")
    fun client(client: ClientContext): Map<String, String?> =
        mapOf("addressHash" to client.addressHash, "userAgentHash" to client.userAgentHash, "address" to null)

    /** A bucket keyed on the caller rather than the address (slice B2). */
    @GetMapping("/api/v1/probe/per-user")
    @RateLimited(RateLimitBucket.INVITE_CREATE_USER)
    fun perUser(): Map<String, String> = mapOf("ok" to "true")

    /** Two buckets on one handler, as `GET /invites/{code}` needs (slice B2). */
    @GetMapping("/api/v1/probe/two-buckets")
    @RateLimited(RateLimitBucket.INVITE_LOOKUP_USER, RateLimitBucket.INVITE_CODE_IP)
    fun twoBuckets(): Map<String, String> = mapOf("ok" to "true")

    /** Names a bucket whose subject is in the request body, which the interceptor cannot read. */
    @GetMapping("/api/v1/probe/wrong-subject")
    @RateLimited(RateLimitBucket.AUTH_LOGIN_EMAIL)
    fun wrongSubject(): Map<String, String> = mapOf("ok" to "true")

    @GetMapping("/api/v1/probe/admin")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    fun admin(): Map<String, String> = mapOf("secret" to "plans")
}

/** Present so the chain test can prove a `CurrentUser` on a public route is a 401, not a 500. */
@Component
class ProbeMarker
