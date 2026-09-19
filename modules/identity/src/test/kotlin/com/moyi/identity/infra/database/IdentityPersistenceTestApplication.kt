package com.moyi.identity.infra.database

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * A Spring context just large enough to test this module's persistence, and
 * test-only — the real application root is `com.moyi.app.MoyiApplication`.
 *
 * It exists because `UserEntity`, `UserRepository` and the mappers are
 * `internal`: nothing in `app` can name them, so nothing in `app` can test
 * them. Rather than widen the visibility to suit the test — which would
 * delete the boundary the Konsist rules exist to hold — the module brings its
 * own context. Component scanning starts from this package, which is where
 * the entities and repositories live.
 */
@SpringBootApplication
internal class IdentityPersistenceTestApplication
