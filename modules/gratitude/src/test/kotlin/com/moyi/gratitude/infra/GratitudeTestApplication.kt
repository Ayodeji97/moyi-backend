package com.moyi.gratitude.infra

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.security.VerifiedEmails
import com.moyi.common.web.idempotency.IdempotencyConfiguration
import com.moyi.identity.api.UserDirectory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
 * **`com.moyi.notification` is scanned too, as of Phase 4 N2**, for the
 * real `Notifications` behind the daily reminder: its ledger, its outbox
 * event and its consumer, so that a reminder test reads the row the push
 * consumer will read. `notification` is a leaf that knows nothing of this
 * module, and its two providers are the logging ones (this module's test
 * `application.yml`).
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
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    basePackages = ["com.moyi.common", "com.moyi.gratitude", "com.moyi.bond", "com.moyi.notification", "com.moyi.media"],
    excludeFilters = [
        // @SpringBootApplication's own two.
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = [GratitudeTestApplication.PUSH_CONSUMERS]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = [GratitudeTestApplication.MEDIA_ROUTES]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = [GratitudeTestApplication.MEDIA_PURGE]),
    ],
)
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.gratitude", "com.moyi.bond", "com.moyi.notification")
@EntityScan("com.moyi.gratitude", "com.moyi.bond")
@EnableJpaRepositories("com.moyi.gratitude", "com.moyi.bond")
@Import(IdempotencyConfiguration::class)
internal class GratitudeTestApplication {
    @Bean
    fun userDirectory(): UserDirectory = FakeUserDirectory()

    /** `media`'s claim asks identity's other port (ADR-0049); here it reads the same fake directory. */
    @Bean
    fun verifiedEmails(directory: UserDirectory): VerifiedEmails = VerifiedEmails { directory.find(it)?.emailVerified == true }

    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }

    /** Phase 4 N3: the provider keeps what it was handed, so a test can read every payload a phone would get. */
    @Bean
    @Primary
    fun capturingPushSender(): CapturingPushSender = CapturingPushSender()

    /** `media.process` times every photo; the application gets its registry from the actuator. */
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

    companion object {
        /**
         * `media`'s own routes, left out: this module reaches `media` through
         * `media.api` alone (the claim and the photo's view, Phase 4 D2), and
         * its tests put a processed photo in place by hand rather than upload
         * one. Everything else of `media` is here, real: the claim's statement,
         * the view's rule and its signed URL (signing needs no storage).
         */
        const val MEDIA_ROUTES = """com\.moyi\.media\.web\..*"""

        /**
         * `media.purge` (Phase 4 D5), left out: what is on trial here is that every
         * erasure releases its media to `media`, which these tests read in
         * `media_assets` and the outbox; the deleting of objects is `media`'s to
         * test against a storage (`MediaPurgeTest`), and this context's storage is
         * an address nothing answers on.
         */
        const val MEDIA_PURGE = """com\.moyi\.media\.service\.PurgeMedia"""

        /**
         * The two push consumers (Phase 4 N3, ADR-0046), `gratitude.notify` and `bond.notify`,
         * left out of the shared context. A dozen tests here count the outbox's deliveries
         * exactly (the withdrawal's one, a failing handler's none), and with these in the
         * context every join, reveal and push would add to the count without being what those
         * tests are about. The tests of the pushes import `NotifyDayMembers` themselves
         * (`DayPushesTest`); `bond.notify`'s are `bond`'s (`BondPushesTest`), and the app's
         * `SecretsNeverLoggedTest` runs with both.
         */
        const val PUSH_CONSUMERS = """com\.moyi\.(gratitude|bond)\.service\.Notify(Day|Bond)Members"""
    }
}
