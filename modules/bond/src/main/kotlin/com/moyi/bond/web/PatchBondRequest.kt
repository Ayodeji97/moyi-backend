package com.moyi.bond.web

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondSettings
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.Change
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalTime
import java.util.Optional

/**
 * The wire format of `PATCH /bonds/{bondId}` (doc 06 §3.3).
 *
 * **`Optional` is how an omitted field is told from a field sent as `null`.**
 * Jackson leaves an absent property at its Kotlin default (`null` here) and
 * deserialises an explicit `null` into an empty `Optional` — so `null` means
 * "not named" and `Optional.empty()` means "clear it". It is not pretty; the
 * alternative is guessing, and guessing means a client that edits the name wipes
 * a reveal time it never mentioned.
 *
 * Only `revealTimeLocal` needs it. The other three settings are not nullable, so
 * for them a plain nullable field already means "not named".
 *
 * `anchorTimezone` is declared here **only in order to refuse it.** FR-027 makes
 * the anchor zone two-party and at most once every 30 days, which is `PATCH
 * /bonds/{bondId}/timezone` in slice B5. A client that sends it here has misread
 * the contract, and a `422` says so — whereas ignoring the property would report
 * success for a change that did not happen.
 */
internal data class PatchBondRequest(
    @field:Size(min = 1, max = Bond.MAX_NAME_LENGTH)
    @field:Pattern(regexp = NOT_ONLY_SPACES, message = "must not be blank")
    val name: String? = null,
    @field:ValidBondType
    val type: String? = null,
    val revealTimeLocal: Optional<String>? = null,
    val strictMode: Boolean? = null,
    val anchorTimezone: String? = null,
) {
    /**
     * `HH:mm`, when the caller sent a value at all.
     *
     * A getter-based constraint rather than `Optional<@Pattern String>`: the
     * container-element form compiles and **does not run**, so `"9am"` sailed
     * past validation and `LocalTime.parse` threw a 500 in [toSettings]. Found
     * by `BondSettingsEndpointTest`, which is the only reason it is not still
     * there.
     */
    @get:AssertTrue(message = "must be a time of day such as 21:00")
    val revealTimeLocalIsTimeOfDay: Boolean
        get() = revealTimeLocal?.orElse(null)?.matches(Regex(TIME_OF_DAY)) ?: true

    /**
     * FR-027: not here. Slice B5's `/bonds/{bondId}/timezone`, with the other
     * member's agreement.
     *
     * A getter-based constraint, so the violation names *this property* rather
     * than the whole object — a class-level constraint would land in Spring's
     * binding result as a global error, and `GlobalExceptionHandler` maps field
     * errors only, so the client would get a `422` with an empty `errors` array
     * and no idea which field to fix. The property is named for what it says.
     */
    @get:AssertTrue(message = "can only be changed through /bonds/{bondId}/timezone, with the other member's agreement")
    val anchorTimezoneNotAllowedHere: Boolean get() = anchorTimezone == null

    /**
     * An empty body would otherwise be a successful write that changes nothing
     * and still moves the version — invalidating the other member's `ETag` for
     * no reason at all. `422` is the honest answer to "change these zero
     * things".
     */
    @get:AssertTrue(message = "must name at least one setting to change")
    val atLeastOneSetting: Boolean
        get() = name != null || type != null || revealTimeLocal != null || strictMode != null

    /**
     * Builds the domain's settings, constructing its value types here at the
     * edge — so the service receives types that cannot be invalid.
     *
     * None of these calls can throw: the same constraints ran during validation,
     * and a request that failed them never reached the handler.
     */
    fun toSettings() =
        BondSettings(
            name = name?.trim(),
            type = type?.trim()?.let(BondType::valueOf),
            revealTimeLocal = revealTimeLocal?.let { Change(it.orElse(null)?.let(LocalTime::parse)) },
            strictMode = strictMode,
        )

    private companion object {
        /** 24-hour, zero-padded — the one shape this API takes (doc 06 §1). */
        const val TIME_OF_DAY = "^([01]\\d|2[0-3]):[0-5]\\d$"

        /** At least one character that is not whitespace, so the violation names `name` itself. */
        const val NOT_ONLY_SPACES = "^(?!\\s*$).+$"
    }
}
