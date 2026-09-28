package com.moyi.bond.web

import com.moyi.bond.domain.RegionZone
import jakarta.validation.constraints.NotBlank

/**
 * The wire format of `PATCH /bonds/{bondId}/timezone` (doc 06 §3.3).
 *
 * The field is named `anchorTimezone`, exactly as `POST /bonds` names it, so a
 * client that got the value wrong reads the same field name in the `422` from
 * either endpoint — and `ValidRegionZone` is the same constraint, asking the
 * domain rather than restating it (`BondConstraints`).
 */
internal data class ChangeTimezoneRequest(
    @field:NotBlank
    @field:ValidRegionZone
    val anchorTimezone: String,
) {
    /** Cannot throw: the same factory ran during validation. */
    fun toZone(): RegionZone = RegionZone.of(anchorTimezone.trim())
}
