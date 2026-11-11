package com.moyi.admin.infra

import com.moyi.bond.api.BondAdminLookup
import com.moyi.common.security.TokenRevocations
import com.moyi.identity.api.PasswordHashing
import com.moyi.identity.api.UserAdminLookup
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * A Spring context just large enough to test this module, test-only. It exists
 * because everything here is `internal`: nothing in `app` can name an entity.
 *
 * It scans this module and `common`, and deliberately not `com.moyi` as a whole:
 * identity and bond are on the classpath for their `api` ports, and scanning
 * them would boot their beans (the breached-password corpus among them) for a
 * test that needs none of it.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.admin", "com.moyi.common"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.admin")
@EntityScan("com.moyi.admin")
@EnableJpaRepositories("com.moyi.admin")
internal class AdminTestApplication {
    /**
     * `common:security` builds the API's JWT chain, which wants this port from
     * the identity module. No test here sends a token, so nobody is revoked.
     */
    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { null }

    /**
     * Identity is not scanned here, so its Argon2 hasher and breach corpus are not built: this
     * stands in for the port. A "hash" is the text behind a prefix, and the breach corpus is
     * [BREACHED]. The real port is exercised by `app`'s sign-in tests.
     */
    @Bean
    fun passwordHashing(): PasswordHashing =
        object : PasswordHashing {
            override fun hash(raw: String): String = "test:$raw"

            override fun matches(
                raw: String,
                hash: String,
            ): Boolean = hash == "test:$raw"

            override fun matchesDummy(raw: String): Boolean = false

            override fun isBreached(raw: String): Boolean = raw == BREACHED
        }

    /** Nobody exists in this context; `app`'s lookup tests run the real ports. */
    @Bean
    fun userAdminLookup(): UserAdminLookup =
        object : UserAdminLookup {
            override fun byId(id: java.util.UUID) = null

            override fun byEmail(email: String) = null
        }

    @Bean
    fun bondAdminLookup(): BondAdminLookup =
        object : BondAdminLookup {
            override fun bondsOf(userId: java.util.UUID) = emptyList<com.moyi.bond.api.BondAdminView>()
        }

    /** No actuator in this context; the sign-in counter needs a registry to count in. */
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

    companion object {
        const val BREACHED = "passwordpassword"
    }
}
