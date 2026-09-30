package com.moyi.bond.web

import com.moyi.bond.domain.RegionZone
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/**
 * The wire format of `PATCH /bonds/{bondId}/timezone` (doc 06 §3.3).
 *
 * The field is named `anchorTimezone`, exactly as `POST /bonds` names it, so a
 * client that got the value wrong reads the same field name in the `422` from
 * either endpoint — and `ValidRegionZone` is the same constraint, asking the
 * domain rather than restating it (`BondConstraints`).
 *
 * The constraints are `POST /bonds`'s, for the same reason: [NOT_ONLY_SPACE]
 * rather than `@NotBlank`, because the two disagree about `U+00A0` and the
 * disagreement was a 500 (ADR-0029 §13). `@NotNull` is what makes the field
 * `required` in the generated document.
 */
internal data class ChangeTimezoneRequest(
    @field:NotNull
    @field:Pattern(regexp = NOT_ONLY_SPACE, message = "must not be blank")
    @field:ValidRegionZone
    val anchorTimezone: String,
) {
    /** Cannot throw: the same factory ran during validation. */
    fun toZone(): RegionZone = RegionZone.of(anchorTimezone.trim())
}
