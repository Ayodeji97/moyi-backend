package com.moyi.gratitude.infra

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.web.idempotency.IdempotencyConfiguration
import com.moyi.identity.api.UserDirectory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * A Spring context just large enough to test this module, and test-only —
 * the real application root is `com.moyi.app.MoyiApplication`. This
 * module's first, following `BondTestApplication`'s own precedent for the
 * reason it gives: everything in `gratitude` is `internal`, so nothing in
 * `app` can name `BondDayEntity`, `BondDayStore` or a controller this slice
 * adds later, and widening their visibility to suit a test would delete the
 * boundary the architecture rules exist to hold.
 *
 * **It scans `common`, `gratitude` and, as of Task 7, `bond` — not
 * `identity`.** Identity is reachable from here only through its `api` port,
 * supplied below as [FakeUserDirectory] — so a test in this module cannot
 * accidentally depend on identity's internals, which is the same guarantee
 * the production code has (ADR-0026). [TokenRevocations] is identity's other
 * implementation of a `common:security` port, and the stand-in here revokes
 * nothing: these tests are about bond-days and entries, and a token the test
 * issuer minted is valid by construction.
 *
 * **`com.moyi.bond` widened the scan for a reason, and it is worth
 * recording.** `EntriesEndpointTest` and `GratitudeCrossTenantTest` need to
 * drive real `bond` endpoints (`createBond`, `leave`) to set up a bond and a
 * membership before they can even reach `POST /entries` — and
 * `BondAccess.membershipOf`'s only implementation, `BondAccessAdapter`, is
 * `internal` to `bond`, unreachable by name from here or from a `@Bean`
 * method in this file. A previous task tried closing that gap with a public
 * `BondMembership.forTesting` factory in `bond.api`; it was reverted, because
 * it reopened the exact forgeability `BondMembership`'s `internal`
 * constructor exists to prevent — any caller with that factory could mint a
 * membership for a bond it was never granted. Widening the scan reopens
 * nothing: `com.moyi.bond`'s classes are `internal` at the *Kotlin* level,
 * but compile to ordinary public bytecode (JVM has no such modifier), so
 * `@SpringBootApplication`'s reflection-based component scan finds and wires
 * the **real** `BondsController`, `BondAccessAdapter` and every `bond` store
 * exactly as `app`'s own root does — this test's Kotlin source still cannot
 * *name* a single one of them, so the compiler-enforced boundary between
 * `gratitude` and `bond`'s internals is untouched. What changes is only
 * which beans exist in this context, not what this module's own code is
 * allowed to import.
 *
 * `@EntityScan`/`@EnableJpaRepositories` and `@ConfigurationPropertiesScan`
 * widen to match, so `bond`'s entities, repositories and `InviteProperties`
 * (bound from `moyi.bond.invite.link-base-url` in this module's own test
 * `application.yml`) all resolve.
 *
 * Identity's migrations still run — they are on the classpath through both
 * other modules' dependencies, and Flyway merges every module's — which
 * costs a few tables nothing here touches.
 *
 * **`@Import(IdempotencyConfiguration::class)` wires `@Idempotent`'s beans**
 * (fix round 1, I7) — one import rather than the four hand-copied `@Bean`
 * methods this file used to carry (the same four `IdempotencyTestApplication`
 * wires for its own context). `POST /bonds/{bondId}/entries` is the first
 * `@Idempotent` handler in this module, so without this a request through
 * `EntriesEndpointTest` would sail past `@Idempotent` as if it were not
 * there at all — found originally by exactly that: a replay test that
 * quietly ran the handler twice. `IdempotencyConfiguration`'s own KDoc has
 * the fuller account, including why its interceptor orders itself after
 * `common:security`'s `RateLimitInterceptor` (fix round 1, I4).
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common", "com.moyi.gratitude", "com.moyi.bond"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.gratitude", "com.moyi.bond")
@EntityScan("com.moyi.gratitude", "com.moyi.bond")
@EnableJpaRepositories("com.moyi.gratitude", "com.moyi.bond")
@Import(IdempotencyConfiguration::class)
internal class GratitudeTestApplication {
    @Bean
    fun userDirectory(): UserDirectory = FakeUserDirectory()

    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }
}
