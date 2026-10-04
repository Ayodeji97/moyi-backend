package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryText
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

// A Bean Validation constraint that **asks the domain** instead of
// restating it — `bond.web.BondConstraints`' own pattern (`ValidRegionZone`,
// `ValidBondType`), copied rather than shared because that file is
// `internal` to `bond`. Its KDoc has the long version of why this shape
// exists at all, and it is worth reading in full: its first draft restated
// the rules at the edge and "every place the two statements disagreed was
// a 500 on a well-formed request — three of them reachable."
//
// This module's own first draft made exactly that mistake, caught in fix
// round 1: `@field:Pattern(NOT_ONLY_SPACE)` plus `@field:Size(max =
// EntryText.MAX_OCTETS)` tried to restate FR-041's blank and octet limits
// at the edge, and got two things wrong at once. `@Size` counts UTF-16
// characters, not UTF-8 octets, so it was never the "octet backstop" its
// own KDoc claimed to be — a 600-grapheme ASCII body or an 8192-emoji body
// both pass it and then blow up inside `EntryText.of` as an uncaught
// `IllegalArgumentException`, which the catch-all turns into a `500` on a
// well-formed request: FR-041's own limits, unreachable as the `422` they
// are supposed to be. And `EntryText.of` judges each limit on a different
// form of the string — blank and the grapheme count on the NFKC-normalised,
// trimmed form, the octet cap on the raw bytes it stores (ruling P12) — so
// even a *correct* restatement at the edge would have to repeat all of that
// and stay in step with it. `@ValidEntryText` below removes the whole class of bug rather than
// patching an instance of it: it runs [EntryText.of] itself and reports
// whatever it complains about, so there is exactly one statement of FR-041,
// in the domain, and the edge only ever asks it.

/**
 * FR-041, asked of the domain rather than restated: [EntryText.of] is run
 * against the field's value, and any [IllegalArgumentException] it throws —
 * a NUL character, an unpaired surrogate, over the octet cap, blank, over
 * the grapheme cap, in the
 * order that factory checks them — becomes this constraint's violation
 * message. Nothing the factory returns is kept here: the request carries the
 * raw string on to the service, which runs the same factory and stores that
 * string exactly as sent.
 *
 * Null passes, as every Bean Validation constraint but `@NotNull` does;
 * `text` still carries `@field:NotNull` so an absent field is a `422`
 * naming it and, separately, appears in the generated OpenAPI document's
 * `required` list.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [EntryTextConstraintValidator::class])
internal annotation class ValidEntryText(
    val message: String = "is not a valid entry",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class EntryTextConstraintValidator : ConstraintValidator<ValidEntryText, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value == null) return true
        // Not pre-trimmed, unlike ValidRegionZone's own validator: the raw
        // field value is what is stored (ruling P12), so it is what is judged.
        // EntryText.of normalises and trims a copy of its own to decide.
        return validate(context) { EntryText.of(value) }
    }
}

/**
 * Runs a domain constructor and reports its complaint as the violation —
 * `bond.web.BondConstraints`' own `validate`, copied for the reason this
 * file's header gives.
 *
 * `IllegalArgumentException` only — the exception `require` throws. Catching
 * `Exception` would turn a genuine bug inside a domain factory into a polite
 * `422` about the user's input, which is exactly the kind of silence this
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
