package com.moyi.app

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.KoPackageProvider
import com.lemonappdev.konsist.api.provider.KoPathProvider
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Executable architecture rules — doc 16 §2.1: "Add Konsist enforcement,
 * which he probably does not — conventions that are not executable decay."
 *
 * Assertions are written by hand against a filtered list rather than with
 * Konsist's own `assertTrue { }` helper, because that helper throws on an
 * empty scope and the domain modules are still empty shells at Phase 0.
 * Each rule is written so it holds vacuously today and bites the moment
 * Phase 1 code lands. Every rule here was verified by introducing a real
 * violation, watching it fail, and reverting — a rule that has never
 * failed is not yet known to work.
 *
 * Every rule below is keyed on the package convention
 * `com.moyi.<module>.<layer>`, so a file that does not follow it is not
 * rejected by these rules — it is invisible to all of them. That is what
 * [`every file in a module declares a com-moyi-module-layer package`]
 * exists to prevent: it makes the convention the other rules assume into
 * a rule of its own.
 */
class ArchitectureTest {
    companion object {
        /**
         * One scan for the whole class. `scopeFromProject()` re-reads and
         * re-parses every Kotlin file in the repository on each call, so
         * calling it per rule multiplies that cost by the number of rules.
         */
        private val project: KoScope by lazy { Konsist.scopeFromProject() }

        /** The domain modules, per doc 25 §6. */
        private val MODULES =
            setOf(
                "identity",
                "bond",
                "gratitude",
                "media",
                "notification",
                "scheduling",
                "admin",
                "analytics",
            )

        /**
         * What each layer inside a module may import *from its own module*.
         *
         * - `api` — the inter-module contract other modules may call.
         *   Self-contained: interfaces and its own DTOs, nothing else, so
         *   a caller never drags module internals along with it.
         * - `domain` — innermost. Models and business rules. Depends on
         *   nothing, which is what keeps it testable without Spring,
         *   a database, or HTTP.
         * - `infra` — JPA entities, repositories, vendor SDKs. Maps to and
         *   from domain; never reaches back up to service or web.
         * - `service` — orchestration. Implements the `api` contract and
         *   coordinates domain and infra in the right order.
         * - `web` — controllers, request/response DTOs, mappers. The HTTP
         *   edge. Goes through service, never straight to infra.
         *
         * Cross-module imports are not restricted here: Kotlin's `internal`
         * already makes anything outside another module's `api` package
         * invisible at compile time, and the `internal` rule below is what
         * keeps that true.
         */
        private val ALLOWED_LAYER_IMPORTS =
            mapOf(
                "api" to emptySet<String>(),
                "domain" to emptySet(),
                "infra" to setOf("domain"),
                "service" to setOf("domain", "infra", "api"),
                "web" to setOf("domain", "service"),
            )

        /** `modules/<name>/src/…` — where a domain module's code lives on disk. */
        private val MODULE_SOURCE_PATH = Regex("""^/modules/([^/]+)/src/""")

        private data class Location(
            val module: String,
            val layer: String,
        )

        /**
         * Resolves `com.moyi.<module>.<layer>...` into its module and
         * layer, or null for anything outside a domain module (`app`,
         * `common`, third-party packages).
         */
        private fun locationOf(qualifiedName: String?): Location? {
            val parts = qualifiedName?.split(".").orEmpty()
            if (parts.size < 4 || parts[0] != "com" || parts[1] != "moyi") return null
            val (module, layer) = parts[2] to parts[3]
            return Location(module, layer)
                .takeIf { module in MODULES && layer in ALLOWED_LAYER_IMPORTS }
        }

        /**
         * Konsist reports paths with the host OS's separator; the rules
         * match on `/` so they behave the same on Linux CI and Windows.
         */
        private val KoPathProvider.normalisedProjectPath: String
            get() = projectPath.replace('\\', '/')

        /**
         * Declarations outside an `api` package that anything beyond their
         * own module could still see. `hasPublicOrDefaultModifier` is the
         * load-bearing part: Kotlin's default is public, so a declaration
         * with no modifier at all is exactly the case this rule is for,
         * while `private` ones are already unreachable and not violations.
         */
        private fun <T> Collection<T>.publicOutsideApi(): List<String>
            where T : KoNameProvider, T : KoPackageProvider, T : KoVisibilityModifierProvider =
            filter { it.hasPublicOrDefaultModifier }
                .filter { declaration ->
                    val location = locationOf(declaration.packagee?.name)
                    location != null && location.layer != "api"
                }.map { it.name }
    }

    @Test
    fun `every file in a module declares a com-moyi-module-layer package`() {
        // The rule that makes the other rules trustworthy. Everything else
        // here resolves a package into a module and a layer and skips what
        // it cannot resolve, so a file in `com.moyi.identity.util` or in
        // `modules/bond` declaring `com.moyi.identity.domain` would be
        // waved through by every rule below rather than caught by them.
        val violations =
            project.files.mapNotNull { file ->
                val directoryModule =
                    MODULE_SOURCE_PATH
                        .find(file.normalisedProjectPath)
                        ?.groupValues
                        ?.get(1)
                        ?: return@mapNotNull null
                if (directoryModule !in MODULES) return@mapNotNull null

                val declared = file.packagee?.name
                val location = locationOf(declared)
                val problem =
                    when {
                        location == null -> "package '$declared' is not com.moyi.<module>.<layer>"
                        location.module != directoryModule -> "declares module '${location.module}'"
                        else -> null
                    }

                problem?.let { "${file.name} in modules/$directoryModule: $it" }
            }

        assertTrue(
            violations.isEmpty(),
            "Every architecture rule is keyed on the package convention " +
                "com.moyi.<module>.<layer>, where <layer> is one of ${ALLOWED_LAYER_IMPORTS.keys}. " +
                "A file that breaks the convention is not rejected by those rules — it is " +
                "silently skipped by all of them. Found: $violations",
        )
    }

    @Test
    fun `layers only depend inwards`() {
        // Import-based, and therefore blind to a fully qualified reference
        // (`com.moyi.identity.service.Foo()` written inline needs no
        // import). Konsist 0.17 exposes no resolved-type graph to close
        // that, and matching raw file text would fire on KDoc and comments.
        // The gap is narrow — Kotlin tooling writes imports, and ktlint
        // keeps them tidy — but it is a gap, not a guarantee.
        val violations =
            project.files
                .flatMap { file ->
                    val from = locationOf(file.packagee?.name) ?: return@flatMap emptyList()
                    val allowed = ALLOWED_LAYER_IMPORTS.getValue(from.layer)

                    file.imports.mapNotNull { import ->
                        val to = locationOf(import.name)
                        val illegal =
                            to != null &&
                                to.module == from.module &&
                                to.layer != from.layer &&
                                to.layer !in allowed
                        if (illegal) "${file.name}: ${from.layer} -> ${to!!.layer}" else null
                    }
                }

        assertTrue(
            violations.isEmpty(),
            "Illegal cross-layer dependency. Allowed: web -> service/domain, " +
                "service -> domain/infra/api, infra -> domain; domain and api depend on nothing. " +
                "Found: $violations",
        )
    }

    @Test
    fun `JPA entities live only in a module's infra layer`() {
        val violations =
            project
                .classes()
                .filter { it.hasAnnotationWithName("Entity", "jakarta.persistence.Entity") }
                .filterNot { it.packagee?.name?.contains(".infra.") == true }
                .map { it.name }

        assertTrue(
            violations.isEmpty(),
            "@Entity is a persistence detail and belongs in infra.database.entities, " +
                "kept separate from the domain model. Found outside infra: $violations",
        )
    }

    @Test
    fun `controllers live only in a module's web layer`() {
        val violations =
            project
                .classes()
                .filter { it.hasAnnotationWithName("RestController", "Controller") }
                .filterNot { it.packagee?.name?.contains(".web.") == true }
                .filterNot { it.packagee?.name?.startsWith("com.moyi.app") == true }
                .map { it.name }

        assertTrue(
            violations.isEmpty(),
            "Controllers are the HTTP edge and belong in the web layer. Found elsewhere: $violations",
        )
    }

    @Test
    fun `declarations outside a module's api package are internal, not public`() {
        // Identified by package convention (com.moyi.<module>.<layer>), not
        // by a ".modules." substring: `modules/` is only the Gradle path,
        // it never appears in a package name. The original form of this
        // rule filtered on that substring and so could never match anything
        // — it passed for four PRs by being unable to fail.
        //
        // Every top-level declaration kind is checked, not just classes: an
        // interface is the *likeliest* leak, since `service` implements the
        // `api` contract and `infra` exposes repositories, and a public
        // interface in either is reachable from a sibling module exactly
        // like a public class would be. Nested and local declarations are
        // excluded deliberately — a member of an `internal` class carries
        // no modifier of its own, so including them reports false
        // violations for declarations nothing outside can reach anyway.
        val violations =
            project.classesAndInterfacesAndObjects(includeNested = false, includeLocal = false).publicOutsideApi() +
                project.typeAliases.publicOutsideApi() +
                project.functions(includeNested = false, includeLocal = false).publicOutsideApi() +
                project.properties(includeNested = false).publicOutsideApi()

        assertTrue(
            violations.isEmpty(),
            "Only a module's api package is public; every other class, interface, object, " +
                "type alias, top-level function and top-level property must be internal, " +
                "so other modules physically cannot reach it. Found public: $violations",
        )
    }

    @Test
    fun `no file imports java-util-Date`() {
        val violations =
            project.files
                .filter { file -> file.imports.any { it.name == "java.util.Date" } }

        assertTrue(
            violations.isEmpty(),
            "java.util.Date must not be used (doc 18 §3) — found in: " +
                violations.joinToString { it.name },
        )
    }

    @Test
    fun `no property is field-injected with Autowired`() {
        // A primary-constructor parameter with @Autowired (needed on test
        // classes — see HealthCheckTest) is constructor injection, not
        // field injection: isConstructorDefined distinguishes the two.
        val violations =
            project
                .properties()
                .filterNot { it.isConstructorDefined }
                .filter { it.hasAnnotationOf(Autowired::class) }

        assertTrue(
            violations.isEmpty(),
            "Constructor injection only (doc 18 §4) — @Autowired field found on: " +
                violations.joinToString { it.name },
        )
    }
}
