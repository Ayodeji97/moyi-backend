package com.moyi.app

import com.lemonappdev.konsist.api.Konsist
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
 */
class ArchitectureTest {
    companion object {
        /** The domain modules, per doc 25 §6. */
        private val MODULES =
            setOf(
                "identity",
                "space",
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
    }

    @Test
    fun `layers only depend inwards`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
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
            Konsist
                .scopeFromProject()
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
            Konsist
                .scopeFromProject()
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
    fun `classes outside a module's api package are internal, not public`() {
        // Identified by package convention (com.moyi.<module>.<layer>), not
        // by a ".modules." substring: `modules/` is only the Gradle path,
        // it never appears in a package name. The original form of this
        // rule filtered on that substring and so could never match anything
        // — it passed for four PRs by being unable to fail.
        val violations =
            Konsist
                .scopeFromProject()
                .classes()
                .filter { klass -> locationOf(klass.packagee?.name)?.layer?.equals("api") == false }
                .filterNot { it.hasInternalModifier }
                .map { it.name }

        assertTrue(
            violations.isEmpty(),
            "Only a module's api package is public; everything else must be internal, " +
                "so other modules physically cannot reach it. Found public: $violations",
        )
    }

    @Test
    fun `no file imports java-util-Date`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
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
            Konsist
                .scopeFromProject()
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
