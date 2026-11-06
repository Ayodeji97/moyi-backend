package com.moyi.admin.infra

import com.moyi.common.security.TokenRevocations
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
}
