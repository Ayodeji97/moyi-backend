package com.moyi.support.web

import com.moyi.support.domain.SupportBody
import com.moyi.support.domain.SupportReference
import com.moyi.support.domain.SupportSubject
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

/**
 * The wire format of `POST /support/messages` (FR-102, ADR-0057): `{"subject": …, "body": …, "reference": …}`,
 * the first two required, `reference` optional (decision 11: the person's error reference, absent or null
 * for none). Each edge check asks the domain ([SupportSubject.of], [SupportBody.of], [SupportReference.of]), as
 * `ReportRequest` does, and never restates the rule; a refusal is a `422` naming the field, whose
 * message says why and never repeats the value.
 */
internal data class SupportMessageRequest(
    @field:NotNull
    @field:ValidSupportSubject
    val subject: String? = null,
    @field:NotNull
    @field:ValidSupportBody
    val body: String? = null,
    @field:ValidSupportReference
    val reference: String? = null,
) {
    /** Only for a request that has passed validation. */
    fun supportSubject(): SupportSubject = SupportSubject.of(checkNotNull(subject) { "a support request that was not validated" })

    /** Only for a request that has passed validation. */
    fun supportBody(): SupportBody = SupportBody.of(checkNotNull(body) { "a support request that was not validated" })

    /** Only for a request that has passed validation; null when none was sent. */
    fun supportReference(): SupportReference? = reference?.let(SupportReference::of)

    /** Never the words, nor the reference: they are the sender's to a person, and a log is not that person. */
    override fun toString(): String =
        "SupportMessageRequest(subject=${subject?.let { "(redacted)" }}, body=${body?.let { "(redacted)" }}, " +
            "reference=${reference?.let { "(redacted)" }})"
}

/** What the sender is told: the ticket's id, and when it arrived. Nothing of what they wrote. */
internal data class SupportMessageResponse(
    val id: UUID,
    val createdAt: Instant,
)

/** [SupportSubject.of], asked rather than restated. Null passes; `@NotNull` refuses it. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [SupportSubjectConstraintValidator::class])
internal annotation class ValidSupportSubject(
    val message: String = "is not a subject a support message can have",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class SupportSubjectConstraintValidator : ConstraintValidator<ValidSupportSubject, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || validate(context) { SupportSubject.of(value) }
}

/** [SupportBody.of], asked rather than restated. Null passes; `@NotNull` refuses it. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [SupportBodyConstraintValidator::class])
internal annotation class ValidSupportBody(
    val message: String = "is not a body a support message can have",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class SupportBodyConstraintValidator : ConstraintValidator<ValidSupportBody, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || validate(context) { SupportBody.of(value) }
}

/** [SupportReference.of], asked rather than restated. Null passes: the reference is optional. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [SupportReferenceConstraintValidator::class])
internal annotation class ValidSupportReference(
    val message: String = "is not a reference a support message can carry",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class SupportReferenceConstraintValidator : ConstraintValidator<ValidSupportReference, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || validate(context) { SupportReference.of(value) }
}

/**
 * Runs a domain constructor and reports its complaint as the violation: `gratitude.web`'s own
 * `validate`, copied because each module's web layer is its own (that file's header has why).
 * `IllegalArgumentException` only, and the message added as a literal with `{` and `$` escaped.
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
