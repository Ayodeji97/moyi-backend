package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema

/** A day of the archive (`gratitude.web.DayResponse`): the same two entry fields as today, from the same functions. */
private const val DAY_RESPONSE = "DayResponse"

/** Every schema with a `partnerEntry`: each gets the same shape, or a client would need two readers. */
private val PARTNER_ENTRY_HOLDERS = listOf(TODAY_RESPONSE, DAY_RESPONSE)
private const val PARTNER_ENTRY_PROPERTY = "partnerEntry"
private const val STATUS = "status"
private const val ENTRY = "EntryResponse"
private const val LOCKED_ENTRY = "LockedEntryResponse"

/** The parent: `gratitude.web.PartnerEntryResponse`, the sealed interface both branches implement, by its own name. */
internal const val PARTNER_ENTRY = "PartnerEntryResponse"
private const val SCHEMAS = "#/components/schemas/"
private const val NULL_TYPE = "null"

/** The schema a partner's entry is described by when its `status` is [status]: `REVEALED` -> `RevealedPartnerEntryResponse`. */
internal fun partnerEntrySchemaFor(status: String): String =
    if (status == LOCKED) LOCKED_ENTRY else status.lowercase().replaceFirstChar { it.uppercase() } + "PartnerEntryResponse"

private const val LOCKED = "LOCKED"

/**
 * Describes a partner's entry as **one parent schema and one child per value of its `status`** (#140), the shape
 * a generator turns into a sealed class with a subclass per value.
 *
 * **What it replaces.** `partnerEntry` was a `oneOf` of `EntryResponse` and `LockedEntryResponse` with a
 * `status` discriminator mapping `SUBMITTED`, `REVEALED` and `DELETED` all to `EntryResponse`. Legal, and
 * undecodable in practice: OpenAPI Generator 7.26 (`kotlin`, `multiplatform`) emitted a sealed class that nothing
 * extended, so every non-null `partnerEntry` failed to decode, and kotlinx.serialization cannot map several
 * discriminator values to one class in any case. Measured by generating from the document before and after:
 * the client had to hand-write a dispatcher for the one field.
 *
 * **What it is.**
 * - `PartnerEntryResponse`: an object with one property, `status`, required, enumerated, and the
 *   `discriminator`, mapping each value to exactly one schema.
 * - One child per value, each `allOf: [PartnerEntryResponse, {its fields}]`: `SubmittedPartnerEntryResponse`,
 *   `RevealedPartnerEntryResponse` and `DeletedPartnerEntryResponse` carry the fields of an entry; the existing
 *   `LockedEntryResponse` carries the author. **No child declares `status` again**: it is inherited, and a
 *   kotlinx.serialization subclass with a property named like the class discriminator does not serialise.
 * - `partnerEntry`, on today and on a day of the archive: `oneOf: [PartnerEntryResponse, null]`.
 * - `EntryResponse` loses the `allOf` that tied it to the parent and keeps its own fields, `status` among them: it
 *   is what `myEntry` and every other route return, where nothing is discriminated, and a child of the parent
 *   would have been generated as a subclass of it.
 *
 * **The bytes on the wire do not change.** Every body that validated before validates now: the four statuses are
 * the same four, and each child has the fields its branch had.
 *
 * **Nothing is restated.** The values are the two branches' own `status` enums as springdoc read them from the
 * domain (`EntryStatus`, `LockedEntryStatus`), and a child's fields are `EntryResponse`'s or
 * `LockedEntryResponse`'s own. A value added to `EntryStatus` becomes a child with nothing written here.
 *
 * Runs after every function that reads an entry's fields where springdoc put them (`allOf.last()`), and before
 * [requireResponseFields], which marks the children's fields required with everyone else's.
 */
internal fun documentPartnerEntry(api: OpenAPI) {
    val schemas = api.components.schemas
    val entry = schemas[ENTRY]?.allOf?.lastOrNull() ?: return
    val locked = schemas[LOCKED_ENTRY]?.allOf?.lastOrNull() ?: return
    val entryStatuses =
        entry.properties[STATUS]
            ?.enum
            .orEmpty()
            .map(Any::toString)
    val statuses =
        entryStatuses +
            locked.properties[STATUS]
                ?.enum
                .orEmpty()
                .map(Any::toString)

    schemas[ENTRY] = entry.description(ENTRY_DESCRIPTION)
    schemas[PARTNER_ENTRY] = parent(statuses)
    entryStatuses.forEach { status ->
        schemas[partnerEntrySchemaFor(status)] = child(withoutStatus(entry), "$CHILD_DESCRIPTION `$status`.")
    }
    schemas[LOCKED_ENTRY] = child(withoutStatus(locked), LOCKED_DESCRIPTION)

    PARTNER_ENTRY_HOLDERS.forEach { holder ->
        schemas[holder]?.properties?.get(PARTNER_ENTRY_PROPERTY)?.let { property ->
            property.discriminator = null
            property.oneOf =
                listOf(Schema<Any>().`$ref`(SCHEMAS + PARTNER_ENTRY), Schema<Any>().apply { types = setOf(NULL_TYPE) })
        }
    }
}

private fun parent(statuses: List<String>): Schema<*> =
    ObjectSchema()
        .description(PARENT_DESCRIPTION)
        .addProperty(STATUS, StringSchema().apply { enum = statuses })
        .addRequiredItem(STATUS)
        .discriminator(
            Discriminator()
                .propertyName(STATUS)
                .mapping(statuses.associateWith { SCHEMAS + partnerEntrySchemaFor(it) }),
        )

private fun child(
    fields: Schema<*>,
    description: String,
): Schema<*> =
    Schema<Any>()
        .description(description)
        .allOf(listOf(Schema<Any>().`$ref`(SCHEMAS + PARTNER_ENTRY), fields))

/** A copy of [fields] without `status`, which a child inherits from the parent. */
private fun withoutStatus(fields: Schema<*>): Schema<*> =
    ObjectSchema().apply {
        properties = fields.properties.filterKeys { it != STATUS }.toMap(LinkedHashMap())
        required = fields.required.orEmpty().filter { it != STATUS }
    }

private const val PARENT_DESCRIPTION =
    "The other member's entry, as the caller is shown it. `status` says which shape this is, and the schema each " +
        "value maps to is the whole of what is sent: LOCKED is an entry written and not yet revealed, and carries " +
        "nothing but its author; REVEALED is the entry in full; DELETED is its tombstone, erased after it was revealed " +
        "(its `text` is null). An entry erased before it was ever revealed is not shown at all: the field is null."

private const val CHILD_DESCRIPTION = "The other member's entry, with every field an entry has, when its `status` is"

private const val LOCKED_DESCRIPTION =
    "The other member's entry while it is locked: written, and not revealed to the caller yet. It carries its author " +
        "and its `status`, LOCKED, and nothing else."

private const val ENTRY_DESCRIPTION =
    "An entry with every field it has. The caller's own entry is always this shape; the other member's is " +
        "`PartnerEntryResponse`."
