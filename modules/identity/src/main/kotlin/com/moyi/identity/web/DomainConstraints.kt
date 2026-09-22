package com.moyi.identity.web

import com.moyi.identity.domain.BreachedPasswordCorpus
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
 * ADR-0012's other half: the password is not in the breached-password corpus.
 *
 * **A second annotation rather than another question inside [ValidPassword],
 * and the reason is the wire format.** A `FieldViolation` carries the
 * constraint's own name as its `code`, so one annotation asking both questions
 * would report "too short" and "already breached" as the same
 * `VALID_PASSWORD` — leaving a client able to tell them apart only by matching
 * on the English `message`. That is precisely what [com.moyi.common.web.ErrorCode]'s
 * contract exists to prevent, and it is not academic here: `states.md` §1c
 * gives the breach case its own designed copy on the sign-up screen, so the
 * client has to be able to recognise it. Two annotations, two codes —
 * `VALID_PASSWORD` and `NOT_BREACHED`.
 *
 * The check lives at the edge rather than inside [Password] or in
 * `RegisterUser` because of what it *is*: a property of the submitted value
 * alone, needing no database, no user and no context — the definition of input
 * validation. Putting it on [Password] was the alternative, and it is
 * tempting, since the type would then mean "acceptable password" in full and
 * no caller could construct one that skips the check. It was rejected because
 * it makes a domain value type unconstructible without a 17 MB piece of
 * infrastructure, which would put a file load into every test that needs a
 * password.
 *
 * **The limit of that choice, stated rather than left to be discovered:**
 * [Password] guarantees shape, not absence from the corpus, so a
 * `RegistrationCommand` built by some future caller that does not come through
 * this annotation would skip the check. Today `RegisterRequest.toCommand` is
 * the only thing that builds one. The moment a second caller appears, this
 * moves to the service.
 *
 * `POST /auth/reset-password` inherits the rule by carrying the same
 * annotation, which is the reuse that matters.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NotBreachedValidator::class])
internal annotation class NotBreached(
    val message: String = BREACHED_MESSAGE,
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/**
 * **"matches a list", not "has appeared in a breach".**
 *
 * A Bloom filter is one-sided: roughly once in a thousand registrations it
 * matches a password that was never breached at all. Telling that person their
 * password "has appeared in a data breach" is a false statement about
 * something they care about, and ADR-0012 asks for copy that "explains rather
 * than blames". This sentence is true in both cases — the value did match the
 * list — and it names the problem as exposure rather than weakness.
 *
 * The client is expected to replace it with `copy.md`'s warmer wording, which
 * is what the `NOT_BREACHED` code is for; this is the fallback for anything
 * reading the API directly.
 */
internal const val BREACHED_MESSAGE =
    "matches a list of passwords exposed in data breaches, so it is not safe to use — please choose another"

internal class NotBreachedValidator(
    private val corpus: BreachedPasswordCorpus,
) : ConstraintValidator<NotBreached, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        val password = value?.takeIf(String::isNotBlank)?.let(::shapedOrNull)
        return if (password != null && corpus.contains(password)) reject(context, BREACHED_MESSAGE) else true
    }

    /**
     * Null for a password [ValidPassword] will already reject.
     *
     * Shape is that constraint's job, and reporting it here too would produce
     * two violations for one mistake. `IllegalArgumentException` only — the
     * exception `require` throws; catching `Exception` would swallow a genuine
     * bug inside a domain factory and quietly wave the password through.
     *
     * The exception is swallowed on purpose, which is the one case detekt's
     * rule cannot see: it is not lost, it is *another constraint's* to report,
     * and re-raising or logging it here is exactly what produces the second
     * error.
     */
    @Suppress("SwallowedException")
    private fun shapedOrNull(value: String): Password? =
        try {
            Password.of(value)
        } catch (invalidShape: IllegalArgumentException) {
            null
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
        reject(context, rejected.message ?: "is not valid")
    }

/**
 * Replaces the default violation with [message], and always returns `false`.
 *
 * `addExpressionVariable` is not used and the message is added as a literal:
 * Hibernate Validator interpolates `{}` and `${}` in a template, and a message
 * that ever contained either would otherwise become an expression. None do
 * today; this is the line that keeps that from mattering — and it is shared by
 * both validators so that a rule added to one cannot arrive unescaped.
 */
private fun reject(
    context: ConstraintValidatorContext,
    message: String,
): Boolean {
    context.disableDefaultConstraintViolation()
    context
        .buildConstraintViolationWithTemplate(message.replace("{", "\\{").replace("$", "\\$"))
        .addConstraintViolation()
    return false
}
