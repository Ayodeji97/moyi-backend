package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryReport
import com.moyi.gratitude.domain.ReportDetail
import com.moyi.gratitude.domain.ReportReason
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.NotNull
import java.util.UUID
import kotlin.reflect.KClass

/**
 * The wire format of `POST /entries/{entryId}/report` (FR-100, ADR-0053):
 * `{"reason": …, "detail": …}`, the detail optional.
 *
 * Both fields are text, so that a value that is not one of the reasons, or a
 * detail too long, is a `422` naming the field and not an unreadable body.
 * Each edge check asks the domain ([ReportReason.named], [ReportDetail.of]),
 * as `ValidEntryText` does, and never restates it. A key that is not one of
 * these two is ignored, as the application's JSON reading does everywhere but
 * the reaction body: nothing here is shown to anybody, so an ignored key
 * cannot make a body say two things to two people.
 */
internal data class ReportRequest(
    @field:NotNull
    @field:ValidReportReason
    val reason: String? = null,
    @field:ValidReportDetail
    val detail: String? = null,
) {
    /** The reason named. Only for a request that has passed validation. */
    fun reportReason(): ReportReason = checkNotNull(reason?.let(ReportReason::named)) { "a report request that was not validated" }

    /** The detail, if one was sent. Only for a request that has passed validation. */
    fun reportDetail(): ReportDetail? = detail?.let(ReportDetail::of)

    /** Never the detail, and the reason only once it is known to be one: until then either is whatever the caller typed. */
    override fun toString(): String = "ReportRequest(reason=${reason?.let(ReportReason::named)}, detail=${detail?.let { "(redacted)" }})"
}

/** What the reporter is told: their report's id, the entry it names, and the reason they gave. Nothing of what is decided. */
internal data class ReportResponse(
    val id: UUID,
    val entryId: UUID,
    val reason: ReportReason,
) {
    companion object {
        fun of(report: EntryReport): ReportResponse = ReportResponse(report.id, report.entryId.value, report.reason)
    }
}

/** [ReportReason.named], asked rather than restated. Null passes; `@NotNull` refuses it. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ReportReasonConstraintValidator::class])
internal annotation class ValidReportReason(
    val message: String = "is not a reason a report can give",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class ReportReasonConstraintValidator : ConstraintValidator<ValidReportReason, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || ReportReason.named(value) != null
}

/**
 * [ReportDetail.of], asked rather than restated: whatever it refuses becomes
 * this constraint's message, which says why and never repeats the value. Null
 * passes: the detail is optional.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ReportDetailConstraintValidator::class])
internal annotation class ValidReportDetail(
    val message: String = "is not a detail a report can carry",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class ReportDetailConstraintValidator : ConstraintValidator<ValidReportDetail, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || validate(context) { ReportDetail.of(value) }
}
