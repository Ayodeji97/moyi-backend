package com.moyi.identity.web

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Bean Validation constraints that **ask the domain** instead of restating it.
 *
 * The first version of `RegisterRequest` carried `@Email`, `@Size(min = 12,
 * max = 128)` and a hand-written byte-limit check, and its KDoc argued that
 * repeating the domain's rules at the edge was worth it because the domain's
 * `require` produces a 500 while an annotation produces a renderable 422.
 * That argument was right about the consequence and wrong about the fix: two
 * statements of one rule do not stay identical, and **every place they
 * disagreed was a 500 on a well-formed request.** Three were reachable, all
 * confirmed against the running application:
 *
 * - `a@b` satisfies `@Email` — which deliberately does not require a dot —
 *   and fails `Email`'s own shape check.
 * - A 12-character password containing a combining accent composes to 11
 *   under NFKC, and `@Size` measured the string *before* normalisation.
 * - 128 `ﬁ` ligatures expand to 256 characters under NFKC, breaking the
 *   maximum from the other direction.
 *
 * Delegating removes the class of bug rather than the three instances: the
 * domain is the only definition, so the two cannot drift, and a rule added
 * there becomes a 422 here without anyone remembering to mirror it.
 *
 * The violation message is the domain's own. That is safe by construction —
 * those messages are written not to echo their input (doc 18 §5), and
 * `UserTest` asserts it — and it means the sentence a user reads is
 * maintained next to the rule that produced it.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [EmailConstraintValidator::class])
internal annotation class ValidEmail(
    val message: String = "is not a valid email address",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class EmailConstraintValidator : ConstraintValidator<ValidEmail, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        // Null and blank are `@NotBlank`'s job. A constraint that also
        // reports them produces two errors for one mistake.
        if (value.isNullOrBlank()) return true
        // Trimmed, because that is what `toCommand` stores: a surrounding
        // space is a typing accident, not part of an address. Doing it only
        // in `toCommand` meant `@Email` rejected the request before the trim
        // could ever apply — the comment there described a behaviour the code
        // did not have.
        return validate(context) { Email(value.trim()) }
    }
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PasswordConstraintValidator::class])
internal annotation class ValidPassword(
    val message: String = "is not a valid password",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class PasswordConstraintValidator : ConstraintValidator<ValidPassword, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value.isNullOrBlank()) return true
        // Not trimmed: ADR-0012 accepts spaces in a password, and silently
        // removing them would make the password the user typed unenterable.
        return validate(context) { Password.of(value) }
    }
}

/**
 * Runs a domain constructor and reports its complaint as the violation.
 *
 * `IllegalArgumentException` only — the exception `require` throws. Catching
 * `Exception` here would turn a genuine bug inside a domain factory into a
 * polite 422 about the user's input, which is exactly the kind of silence
 * this codebase keeps having to dig back out.
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
            // `addExpressionVariable` is not used and the message is added as
            // a literal: Hibernate Validator interpolates `{}` and `${}` in a
            // template, and a domain message that ever contained either would
            // otherwise become an expression. None do today; this is the line
            // that keeps that from mattering.
            .buildConstraintViolationWithTemplate(
                rejected.message?.replace("{", "\\{")?.replace("$", "\\$") ?: "is not valid",
            ).addConstraintViolation()
        false
    }
