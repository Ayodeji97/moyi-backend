package com.moyi.app

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Doc 25 §7 step 8's first three Konsist rules. Assertions are written by
 * hand against a filtered list (not Konsist's own `assertTrue { }` helper)
 * because that helper throws on an empty scope — and the `modules` subtree
 * is still empty shells at Phase 0, with real content arriving in Phase 1.
 * A rule that can only be checked against code that doesn't exist yet
 * would be dead weight; these are written so they hold today and keep
 * holding once the modules subtree has content.
 */
class ArchitectureTest {
    @Test
    fun `classes outside a module's api package are internal, not public`() {
        val violations =
            Konsist
                .scopeFromProject()
                .classes()
                .filter { it.packagee?.name?.contains(".modules.") == true }
                .filterNot { it.packagee?.name?.endsWith(".api") == true }
                .filterNot { it.hasInternalModifier }

        assertTrue(
            violations.isEmpty(),
            "Classes outside a modules/<name>/api package must be internal: " +
                violations.joinToString { it.name },
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
