package com.moyi.gratitude.web

import com.moyi.gratitude.domain.ReactionType
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.NotNull
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
import kotlin.reflect.KClass

/**
 * The body of `PUT /entries/{entryId}/reaction` (FR-048): `{"type": …}`,
 * one of the names of [ReactionType].
 *
 * [type] is text here, and nullable, so that a value that is not one of the
 * set reaches Bean Validation and is the `422` that names the field, as a
 * missing one is (`PatchEntryRequest.text` is nullable for the same
 * reason). Which names are in the set is not written here:
 * [ValidReactionType] asks the domain. The contract states it as a required
 * enum (`contracts.OpenApiConfiguration`).
 *
 * **Read by [ReactionRequestReader], which takes one key, once.** The JSON
 * library's own reading ignores a key it does not know and keeps the last of
 * a key sent twice (`bond.web.EndBondRequestReader` has both, and why they
 * are not mapper settings). Here that would set a reaction the other person
 * sees from a body that said two things, or said something this API has
 * never heard of.
 */
@JsonDeserialize(using = ReactionRequestReader::class)
internal data class ReactionRequest(
    @field:NotNull
    @field:ValidReactionType
    val type: String? = null,
) {
    /** The type named. Only for a request that has passed validation. */
    fun reactionType(): ReactionType = checkNotNull(type?.let(ReactionType::named)) { "a reaction request that was not validated" }

    /** Never the value: until it has been validated it is whatever the caller typed, of any length. */
    override fun toString(): String = "ReactionRequest(type=${type?.let(ReactionType::named)})"
}

/**
 * Reads a [ReactionRequest], and nothing that merely resembles one.
 *
 * - A body that is not an object, a key other than `type`, `type` twice, or
 *   a value that is an object or an array: the `400` any unreadable body
 *   gets. The message names no key and quotes no value; the handler does not
 *   send it anyway.
 * - A plain value is passed on as its text, whatever it is: a number or a
 *   boolean is then simply not one of the names, and is refused with every
 *   other value that is not, by [ValidReactionType]. Nothing is coerced into
 *   a type: `3` is not the fourth of them.
 *
 * A top-level JSON `null` never reaches [deserialize]; it is no body.
 */
internal class ReactionRequestReader : ValueDeserializer<ReactionRequest>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): ReactionRequest {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw context.wrongTokenException(parser, ReactionRequest::class.java, JsonToken.START_OBJECT, "an object")
        }
        var seen = false
        var type: String? = null
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            if (parser.currentName() != TYPE || seen) {
                context.reportInputMismatch<Nothing>(ReactionRequest::class.java, "one key, once")
            }
            seen = true
            val value = parser.nextToken()
            type =
                when {
                    value == JsonToken.VALUE_NULL -> null
                    value.isScalarValue -> parser.string
                    else -> throw context.wrongTokenException(parser, String::class.java, JsonToken.VALUE_STRING, "a name")
                }
        }
        return ReactionRequest(type)
    }

    private companion object {
        /** The property's own name, so the key read here cannot drift from the one the contract is generated from. */
        val TYPE: String = ReactionRequest::type.name
    }
}

/**
 * FR-048's fixed set, asked of the domain rather than restated
 * ([ReactionType.named]): exact, so `heart` is not `HEART`. The message
 * does not repeat the value. Null passes, as with every constraint but
 * `@NotNull`, which the field also carries.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ReactionTypeConstraintValidator::class])
internal annotation class ValidReactionType(
    val message: String = "is not a reaction type",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class ReactionTypeConstraintValidator : ConstraintValidator<ValidReactionType, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || ReactionType.named(value) != null
}
