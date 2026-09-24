package com.moyi.bond.web

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalTime

/**
 * The wire format of `POST /bonds` (doc 06 §3.3).
 *
 * A separate type from [BondDraft] rather than the same object passed down,
 * for the reason `RegisterRequest` gives: the wire format is a contract with
 * clients and the draft is a contract with the service, and letting one type
 * be both means a rename for a client's benefit silently rewrites the
 * service's signature.
 *
 * `type` and the two zones are `String`s with constraints rather than typed
 * fields, so a wrong value is a `422` naming the field rather than Jackson's
 * `400` for the whole body.
 */
internal data class CreateBondRequest(
    @field:NotBlank
    @field:Size(max = Bond.MAX_NAME_LENGTH)
    val name: String,
    @field:NotBlank
    @field:ValidBondType
    val type: String,
    @field:NotBlank
    @field:ValidRegionZone
    val anchorTimezone: String,
    /**
     * `HH:mm`, optional. FR-062 is a `SHOULD`, OQ-07 is open on what it means
     * when both members have written before it, and `states.md` §1 took it out
     * of onboarding on exactly those grounds — so a client may set it and none
     * has to.
     */
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 21:00")
    val revealTimeLocal: String? = null,
    /** The creator's own zone for reminders; absent means the anchor zone for now (doc 04 §6). */
    @field:ValidRegionZone
    val reminderTimezone: String? = null,
) {
    /**
     * Builds the draft, constructing the domain's value types here at the edge
     * rather than in the service — so the service receives types that cannot
     * be invalid and has no failure mode left to handle.
     *
     * None of these constructors can throw: the same factories ran during
     * validation, and a request that failed them never reached the handler.
     */
    fun toDraft(creator: UserId) =
        BondDraft(
            creator = creator,
            type = BondType.valueOf(type.trim()),
            name = name.trim(),
            anchorTimezone = RegionZone.of(anchorTimezone.trim()),
            revealTimeLocal = revealTimeLocal?.let(LocalTime::parse),
            reminderTimezone = reminderTimezone?.takeIf { it.isNotBlank() }?.let { RegionZone.of(it.trim()) },
        )

    private companion object {
        /** 24-hour, zero-padded. `LocalTime.parse` would accept seconds and offsets; the API takes one shape. */
        const val TIME_OF_DAY = "^([01]\\d|2[0-3]):[0-5]\\d$"
    }
}
