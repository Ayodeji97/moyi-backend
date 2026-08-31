import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * The version catalog, reachable from a precompiled script plugin.
 *
 * Gradle's generated `LibrariesForLibs` typed accessor (the `libs.foo.bar`
 * form available in ordinary build scripts) is not reliably generated for
 * precompiled script plugins inside an included build, so convention
 * plugins read the catalog through this plain, always-available extension
 * API instead. Matches the course's own `VersionCatalogExt.kt`.
 */
val Project.libraries: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
