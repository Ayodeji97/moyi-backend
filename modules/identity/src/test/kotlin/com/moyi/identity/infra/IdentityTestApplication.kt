package com.moyi.identity.infra

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * A Spring context just large enough to test this module, and test-only — the
 * real application root is `com.moyi.app.MoyiApplication`.
 *
 * It exists because everything in this module is `internal`: nothing in `app`
 * can name `UserEntity`, `RegisterUser` or `RegistrationController`, so
 * nothing in `app` can test them. Rather than widen the visibility to suit a
 * test — which would delete the boundary the Konsist rules exist to hold —
 * the module brings its own context.
 *
 * It scans `com.moyi` rather than this package, so the tests exercise the
 * same `common` beans the application wires: the shared error handler, the
 * injected `Clock`, the `IdGenerator`. A module whose tests stub those would
 * be testing a system that is never deployed.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi"])
@ConfigurationPropertiesScan("com.moyi")
@EntityScan("com.moyi")
@EnableJpaRepositories("com.moyi")
internal class IdentityTestApplication
