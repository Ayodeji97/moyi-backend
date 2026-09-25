package com.moyi.bond.infra

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.identity.api.UserDirectory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * A Spring context just large enough to test this module, and test-only — the
 * real application root is `com.moyi.app.MoyiApplication`.
 *
 * It exists for the reason `IdentityTestApplication` does: everything in this
 * module is `internal`, so nothing in `app` can name `BondEntity`,
 * `CreateBond` or `BondsController`, and widening their visibility to suit a
 * test would delete the boundary the architecture rules exist to hold.
 *
 * **It scans `common` and `bond`, not identity.** Identity is reachable from
 * here only through its `api` port, and that port is supplied below as
 * [FakeUserDirectory] — so a test in this module cannot accidentally depend on
 * identity's internals, which is the same guarantee the production code has.
 * [TokenRevocations] is identity's other implementation of a `common:security`
 * port, and the stand-in here revokes nothing: these tests are about bonds,
 * and a token the test issuer minted is valid by construction.
 *
 * Identity's migrations still run — they are on the classpath through the
 * `api` dependency, and Flyway merges every module's — which costs a few
 * tables nothing here touches.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common", "com.moyi.bond"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.bond")
@EntityScan("com.moyi.bond")
@EnableJpaRepositories("com.moyi.bond")
internal class BondTestApplication {
    @Bean
    fun userDirectory(): UserDirectory = FakeUserDirectory()

    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }
}
