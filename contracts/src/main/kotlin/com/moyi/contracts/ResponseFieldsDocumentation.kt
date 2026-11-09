package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import java.util.Collections
import java.util.IdentityHashMap

private const val SCHEMAS = "#/components/schemas/"

/**
 * Marks **every field of every object a success response can carry** as `required` (#138).
 *
 * **Why every field, and not every non-null one.** The application's JSON mapper writes a property whose value is
 * null as `null`: no response type excludes nulls, and `OpenApiContractTest` and `ArchitectureTest` hold both
 * halves of that. So a field of a response is always sent, and absence is never a value. A nullable field is
 * therefore required too, with `null` in its type (springdoc reads that from the Kotlin `?`), which is the one
 * combination that lets a client tell `"archivedAt": null` from a field the server never sent. Required only where
 * non-null would have left exactly the fields where that difference matters undocumented.
 *
 * **Why by rule.** Until now each schema that needed it was given its list by hand (an entry's, a photo's, a voice
 * note's), and every response type added since had none, so a generated client made every field of
 * `LoginResponse` optional. springdoc can derive `required` from Kotlin nullability, through Jackson 2's Kotlin
 * module, and `SchemaNullabilityPin` turns that off on purpose: it would mark request fields too, and from a
 * dependency's presence rather than from a decision. This rule covers responses only, and says why.
 *
 * **What it reaches.** From each `2xx` response's schema, every schema it refers to, through properties, array
 * items, `allOf`, `oneOf`, `anyOf`, map values and a discriminator's mapping, named or inline. Nothing a
 * request body uses is reached today, and `OpenApiContractTest` fails if that changes, since a request's
 * optional field must not become required by being shared with a response. `ProblemDetail` is never
 * reached: it is only ever a `4xx` or `5xx` body, and some of its members are absent by design.
 */
internal fun requireResponseFields(api: OpenAPI) {
    responseSchemas(api).forEach(::requireAllFields)
}

/** The schemas every `2xx` response can reach, named and inline, each once (by identity: two equal schemas are two fields). */
private fun responseSchemas(api: OpenAPI): List<Schema<*>> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Schema<*>, Boolean>())
    val reached = mutableListOf<Schema<*>>()
    val names = mutableSetOf<String>()

    fun visit(schema: Schema<*>?) {
        if (schema == null || !seen.add(schema)) return
        val name = schema.`$ref`?.removePrefix(SCHEMAS)
        if (name != null) {
            if (names.add(name)) visit(api.components.schemas[name])
            return
        }
        reached += schema
        schema.properties?.values?.forEach(::visit)
        visit(schema.items)
        schema.allOf?.forEach(::visit)
        schema.oneOf?.forEach(::visit)
        schema.anyOf?.forEach(::visit)
        (schema.additionalProperties as? Schema<*>)?.let(::visit)
        // How a generator reaches a parent's children (`PartnerEntryResponse`'s): they are in no `oneOf`.
        schema.discriminator
            ?.mapping
            ?.values
            ?.forEach { visit(Schema<Any>().`$ref`(it)) }
    }

    api.paths.values
        .flatMap { it.readOperations() }
        .flatMap { operation -> operation.responses.filterKeys { it.startsWith("2") }.values }
        .flatMap { response -> response.content?.values.orEmpty() }
        .forEach { visit(it.schema) }
    return reached
}

private fun requireAllFields(schema: Schema<*>) {
    schema.properties
        ?.keys
        ?.filter { it !in schema.required.orEmpty() }
        ?.forEach(schema::addRequiredItem)
}
