package com.moyi.gratitude.infra

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.identity.api.UserDirectory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
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
 * **It scans `common` and `gratitude`, not `bond` or `identity`.** Both are
 * reachable from here only through their `api` ports, supplied below as
 * [FakeBondAccess] and [FakeUserDirectory] — so a test in this module cannot
 * accidentally depend on either module's internals, which is the same
 * guarantee the production code has (ADR-0026). [TokenRevocations] is
 * identity's other implementation of a `common:security` port, and the
 * stand-in here revokes nothing: these tests are about bond-days and
 * entries, and a token the test issuer minted is valid by construction.
 *
 * Both other modules' migrations still run — they are on the classpath
 * through `gratitude`'s dependency on them, and Flyway merges every
 * module's — which costs a few tables nothing here touches.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common", "com.moyi.gratitude"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.gratitude")
@EntityScan("com.moyi.gratitude")
@EnableJpaRepositories("com.moyi.gratitude")
internal class GratitudeTestApplication {
    @Bean
    fun userDirectory(): UserDirectory = FakeUserDirectory()

    @Bean
    fun bondAccess(): BondAccess = FakeBondAccess()

    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }
}
