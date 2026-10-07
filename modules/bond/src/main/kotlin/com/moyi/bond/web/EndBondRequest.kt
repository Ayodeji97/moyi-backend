package com.moyi.bond.web

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * The optional body of `POST /bonds/{bondId}/leave` and
 * `POST /bonds/{bondId}/block` (FR-029a).
 *
 * **One type for both, and no default in it.** What an absent
 * [withdrawEntries] means is the one thing the two endings disagree about,
 * so it is decided where each is handled (`BondsController`) and not here: a
 * default written into this class would be right for one of them and
 * silently wrong for the other. No body, `{}` and an explicit `null` are all
 * "absent".
 */
internal data class EndBondRequest(
    /** Whether the caller takes their own entries in this bond back, for both members, for good. */
    @param:JsonDeserialize(using = TrueOrFalse::class)
    val withdrawEntries: Boolean? = null,
)

/**
 * A JSON `true` or `false`, and nothing that merely resembles one.
 *
 * Jackson's own reading of a `Boolean` is generous: `"true"` and `1` are
 * true, so is `2`, and `""` is null. For most flags that is harmless. This
 * one erases what a person wrote, for two people, and cannot be taken back,
 * so a value that is not plainly a boolean is the `400` any unreadable body
 * gets rather than a guess at what was meant: `{"withdrawEntries": ""}` sent
 * to a block would otherwise have withdrawn.
 *
 * A JSON `null` never reaches [deserialize]; it is the property's absence.
 */
internal class TrueOrFalse : ValueDeserializer<Boolean>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Boolean =
        when (parser.currentToken()) {
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            else -> throw context.wrongTokenException(parser, Boolean::class.javaObjectType, JsonToken.VALUE_TRUE, "true or false")
        }
}
