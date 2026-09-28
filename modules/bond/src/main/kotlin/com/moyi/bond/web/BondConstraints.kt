package com.moyi.bond.web

import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.RegionZone
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

// Bean Validation constraints that **ask the domain** instead of restating it.
//
// Identity's DomainConstraints carries the long version of why, and it is
// worth reading: its first draft restated the rules at the edge, and every
// place the two statements disagreed was a 500 on a well-formed request —
// three of them reachable. Delegating removes the class of bug rather than the
// instances, because there is only ever one definition.
//
// Null is allowed for optional settings. Bond types must reject a supplied
// blank too: PATCH omits @NotBlank because an absent type is valid.

/**
 * At least one character the *domain* does not consider whitespace.
 *
 * `@NotBlank` is not this rule. Bean Validation blanks a string with Java's
 * `String.trim`, which removes only characters at or below `U+0020`, while
 * Kotlin's `trim` and `isBlank` also remove every `isSpaceChar` — `U+00A0`,
 * `U+2007`, and the rest. A name of one non-breaking space therefore passed
 * `@NotBlank`, was trimmed to the empty string on its way into the domain, and
 * `require(name.isNotBlank())` turned it into a 500 on a well-formed request.
 * Found by `BondsEndpointTest`; the same hole existed on `PATCH`.
 *
 * `(?U)` is what makes `\S` agree with Kotlin here, and `(?s)` lets `.` cross a
 * newline so a multi-line value is judged by its content rather than its shape.
 * Null passes, as every Bean Validation constraint but `@NotNull` does.
 */
internal const val NOT_ONLY_SPACE = "(?sU).*\\S.*"

/** The anchor or reminder zone: an IANA region id (ADR-0004, doc 04 §6). */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [RegionZoneConstraintValidator::class])
internal annotation class ValidRegionZone(
    val message: String = "is not a region time zone",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class RegionZoneConstraintValidator : ConstraintValidator<ValidRegionZone, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value.isNullOrBlank()) return true
        // Trimmed, because that is what `toDraft` will construct from: a
        // surrounding space is a typing accident, and validating the untrimmed
        // value would reject a request the service would have accepted.
        return validate(context) { RegionZone.of(value.trim()) }
    }
}

/**
 * One of [BondType]'s names, exactly.
 *
 * A `String` field with this constraint rather than a typed `BondType`, so an
 * unknown value is a `422` that names the field (doc 06 §2) instead of
 * Jackson refusing the whole body with a `400` — the client needs to know
 * *which* field it got wrong, and a 400 says only that the request was
 * unreadable.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [BondTypeConstraintValidator::class])
internal annotation class ValidBondType(
    val message: String = "must be one of COUPLE, FRIENDS, FAMILY or PARENT_CHILD",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class BondTypeConstraintValidator : ConstraintValidator<ValidBondType, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || BondType.entries.any { it.name == value.trim() }
}

/**
 * Runs a domain constructor and reports its complaint as the violation.
 *
 * `IllegalArgumentException` only — the exception `require` throws. Catching
 * `Exception` would turn a genuine bug inside a domain factory into a polite
 * 422 about the user's input, which is exactly the kind of silence this
 * codebase keeps having to dig back out.
 *
 * The message is added as a literal with `{` and `$` escaped: Hibernate
 * Validator interpolates both in a template, so a domain message that ever
 * contained one would otherwise become an expression.
 */
private inline fun validate(
    context: ConstraintValidatorContext,
    construct: () -> Unit,
): Boolean =
    try {
        construct()
        true
    } catch (rejected: IllegalArgumentException) {
        context.disableDefaultConstraintViolation()
        context
            .buildConstraintViolationWithTemplate((rejected.message ?: "is not valid").replace("{", "\\{").replace("$", "\\$"))
            .addConstraintViolation()
        false
    }
