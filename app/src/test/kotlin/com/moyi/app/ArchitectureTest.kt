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
         *   from domain; never reaches back up to service or web. May
         *   implement the module's own `api` contract directly when that
         *   contract is pure transport with nothing to orchestrate — the
         *   `notification` module's `EmailSender` is a port, its Resend
         *   client is the adapter, and a service in between would exist
         *   only to satisfy this table. Added 2026-09-23 with that module;
         *   an `api` package holds interfaces and DTOs that depend on
         *   nothing, so importing one is never a dependency on behaviour.
         * - `service` — orchestration. Implements the `api` contract when
         *   there are domain rules to apply, and coordinates domain and
         *   infra in the right order.
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
                "infra" to setOf("domain", "api"),
                "service" to setOf("domain", "infra", "api"),
                "web" to setOf("domain", "service"),
            )

        /** `modules/<name>/src/…` — where a domain module's code lives on disk. */
        private val MODULE_SOURCE_PATH = Regex("""^/modules/([^/]+)/src/""")

        /**
         * A `Membership(` constructor *call*. The open bracket is what makes
         * it a call rather than a `[Membership]` mention in KDoc, and the
         * lookbehind excludes the declaration itself — `data class
         * Membership(` in its own file is not a construction.
         */
        private val MEMBERSHIP_CONSTRUCTION = Regex("""(?<!class )\bMembership\(""")

        /**
         * The one file allowed to mint a `com.moyi.bond.domain.Membership`.
         * Konsist reports a file's name without its extension.
         */
        private const val BOND_ACCESS_GUARD = "BondAccessGuard"

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
         * Names of declarations matching [predicate] that live inside a domain
         * module but outside [layer].
         *
         * Anything `locationOf` cannot resolve — `app`, `common`, a test's own
         * throwaway class — is skipped rather than reported, which is the same
         * contract every other rule here follows: these rules constrain the
         * domain modules, and a file outside them is not in scope rather than
         * in violation.
         */
        private fun <T> Collection<T>.inModuleButNotIn(
            layer: String,
            predicate: (T) -> Boolean,
        ): List<String> where T : KoNameProvider, T : KoPackageProvider =
            filter(predicate)
                .filter { declaration ->
                    val location = locationOf(declaration.packagee?.name)
                    location != null && location.layer != layer
                }.map { it.name }

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
        //
        // **Main sources only, and this rule alone.** Every other rule here
        // scans test sources too, deliberately. This one cannot: a test's job
        // is to observe from outside the thing it tests, and for an HTTP
        // endpoint that means posting a request (web) and then asserting on
        // the rows it wrote (infra) — a web -> infra edge that is wrong in
        // production and is the entire point of the test. Applying the
        // production dependency graph to test code does not make the
        // architecture stronger; it makes the tests assert less. The
        // package-convention rule below still covers every file, so test code
        // cannot escape the other rules by living in an unrecognised package.
        val violations =
            project.files
                .filter { it.normalisedProjectPath.contains("/src/main/") }
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
                "service -> domain/infra/api, infra -> domain/api; domain and api depend on nothing. " +
                "Found: $violations",
        )
    }

    @Test
    fun `JPA entities live only in a module's infra layer`() {
        val violations = project.classes().inModuleButNotIn("infra") { it.hasAnnotationWithName("Entity", "jakarta.persistence.Entity") }

        assertTrue(
            violations.isEmpty(),
            "@Entity is a persistence detail and belongs in infra.database.entities, " +
                "kept separate from the domain model. Found outside infra: $violations",
        )
    }

    @Test
    fun `controllers live only in a module's web layer`() {
        // Both this rule and the entity rule above used to filter on the
        // substring `".web."` / `".infra."`. A controller in the layer's root
        // package — `com.moyi.identity.web`, which is where controllers
        // actually go — has no dot after `web`, so the filter excluded
        // nothing and the rule reported every controller as a violation. It
        // had never run against a controller, because until this slice there
        // were none. That is the third filter in this file to have been
        // structurally unable to do its job (see docs/learning-log.md); all
        // three are now resolved through `locationOf`, which is the only
        // thing that actually understands the package convention.
        val violations = project.classes().inModuleButNotIn("web") { it.hasAnnotationWithName("RestController", "Controller") }

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

    @Test
    fun `a Membership is only ever constructed by BondAccessGuard`() {
        // The spec's §4 and doc 05 §5.5 layer 2: the type is a *proof* that
        // the guard said yes, and a proof anyone can forge is a data class.
        // Text-matched, because Konsist exposes no call graph — the name is
        // distinctive enough that a false positive is a loud build failure
        // rather than a silent pass, which is the right way round.
        val violations =
            project.files
                .filter { it.normalisedProjectPath.contains("/modules/bond/src/main/") }
                .filter { it.name != BOND_ACCESS_GUARD && MEMBERSHIP_CONSTRUCTION.containsMatchIn(it.text) }
                .map { it.name }

        assertTrue(
            violations.isEmpty(),
            "Only $BOND_ACCESS_GUARD may construct a Membership: it is the evidence that the caller's " +
                "access was checked, and anything else minting one is an authorisation check that did not happen. " +
                "Found in: $violations",
        )
    }

    @Test
    fun `a bond service function that names a bond also takes the Membership the guard minted`() {
        // Doc 12 §3.6 asks that "every @RestController method taking a bondId
        // passes through BondAccessGuard". Enforced one layer down, where it
        // is actually checkable: a service function that accepts a BondId
        // without a Membership is one a controller could call having skipped
        // the guard. The guard itself is the one legitimate exception — it is
        // what turns the id into the proof.
        //
        // Matched on the simple type name, so a parameter written as
        // `com.moyi.bond.domain.BondId` evades it — found while verifying this
        // rule with a deliberate violation, which is what that exercise is
        // for. The same gap the layer rule above documents for imports, and
        // narrow for the same reason: Kotlin tooling writes imports and ktlint
        // keeps them tidy. It is a gap, not a guarantee.
        val violations =
            project
                .functions()
                .filter { locationOf(it.packagee?.name) == Location("bond", "service") }
                .filter { it.containingFile.name != BOND_ACCESS_GUARD }
                .filter { function ->
                    function.parameters.any { it.type.name == "BondId" } &&
                        function.parameters.none { it.type.name == "Membership" }
                }.map { "${it.containingFile.name}: ${it.name}" }

        assertTrue(
            violations.isEmpty(),
            "A com.moyi.bond.service function with a BondId parameter must also take the Membership " +
                "BondAccessGuard minted for that bond (doc 05 §5.5, doc 09 §4). Found: $violations",
        )
    }
}
