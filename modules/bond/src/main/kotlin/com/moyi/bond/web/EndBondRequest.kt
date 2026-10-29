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
 *
 * **Absent is a different answer on the two routes, and a client must know
 * it.** On `/leave` an absent flag keeps; on `/block` it is refused, with the
 * `400` an unreadable body gets (ADR-0035 questions 1 and 11). Block's absent
 * flag withdrew until the owner's ruling of 2026-10-21: FR-029a "gives"
 * withdrawal on blocking, and a default was how it gave it, but a default
 * that destroys turns an omission into an erasure, so the caller now says
 * which. On leaving withdrawal is only "offered", where an ordinary parting
 * should not erase a shared record by omission. A client that wants one
 * behaviour on both sends the flag on both.
 *
 * **Read by [EndBondRequestReader], which refuses what it is not sure of.**
 * It was written while block's default withdrew, when anything that turned a
 * caller's "no" into an absence withdrew against their word, and for good.
 * An absence is now refused there, but a misspelt key read as one would
 * still decide a leave for the caller; the reader stays strict.
 */
@JsonDeserialize(using = EndBondRequestReader::class)
internal data class EndBondRequest(
    /** Whether the caller takes their own entries in this bond back, for both members, for good. */
    val withdrawEntries: Boolean? = null,
)

/**
 * Reads an [EndBondRequest], and nothing that merely resembles one.
 *
 * The JSON library's own reading is generous in three ways, each harmless
 * for most bodies and none of them harmless here, where the flag erases
 * what a person wrote, for two people, and cannot be taken back:
 *
 * - **A key it does not know is ignored.** `{"withdrawEntry": false}`,
 *   `{"withdraw_entries": false}` and `{"WithdrawEntries": false}` were each
 *   an absent flag, and on a block an absent flag then withdrew: the caller
 *   declined, in so many words, and was withdrawn from (ADR-0035
 *   decision 11; the review of Task 4, C5a). Forward compatibility is the usual reason to ignore a
 *   key, and it is not worth an erasure nobody asked for.
 * - **A key sent twice is the last one sent.** Which of two contradictory
 *   answers was meant is not this server's to pick.
 * - **A value that looks like a boolean is one.** `"true"` and `1` are true,
 *   so is `2`, and `""` is null: `{"withdrawEntries": ""}` sent to a block
 *   would have withdrawn.
 *
 * Each is refused with the `400` any unreadable body gets. The message
 * names no key and quotes no value; the handler does not send it anyway.
 *
 * A whole-type reader rather than three settings: the first two are mapper
 * or parser-factory features, which would have to be turned on for every
 * request in the application to be turned on for this one.
 *
 * A top-level JSON `null` never reaches [deserialize]; it is no body.
 */
internal class EndBondRequestReader : ValueDeserializer<EndBondRequest>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): EndBondRequest {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw context.wrongTokenException(parser, EndBondRequest::class.java, JsonToken.START_OBJECT, "an object")
        }
        var seen = false
        var withdrawEntries: Boolean? = null
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            if (parser.currentName() != FLAG || seen) {
                context.reportInputMismatch<Nothing>(EndBondRequest::class.java, "one key, once")
            }
            seen = true
            withdrawEntries =
                when (parser.nextToken()) {
                    JsonToken.VALUE_TRUE -> true
                    JsonToken.VALUE_FALSE -> false
                    JsonToken.VALUE_NULL -> null
                    else -> throw context.wrongTokenException(parser, Boolean::class.javaObjectType, JsonToken.VALUE_TRUE, "true or false")
                }
        }
        return EndBondRequest(withdrawEntries)
    }

    private companion object {
        /** The property's own name, so the key read here cannot drift from the one the contract is generated from. */
        val FLAG: String = EndBondRequest::withdrawEntries.name
    }
}
