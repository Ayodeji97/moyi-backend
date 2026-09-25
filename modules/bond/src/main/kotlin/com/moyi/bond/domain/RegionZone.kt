package com.moyi.bond.domain

import java.time.ZoneId

/**
 * An IANA **region** time zone — `Africa/Lagos`, never `UTC`, `+01:00` or
 * `Etc/GMT-1`.
 *
 * Two different things in this system are one of these: a Bond's anchor zone,
 * which decides what "today" means for both members (ADR-0004), and a
 * member's own zone, which decides when their reminder arrives. Doc 04 §6
 * calls conflating them the most likely source of a subtle production bug;
 * they share a type because they are the same *kind* of value, and the field
 * names are what keep them apart.
 *
 * **Why a fixed offset is refused.** An offset is a fact about one moment,
 * not a place: `+01:00` is Lagos all year and London only in summer. A bond
 * anchored on an offset would see its day boundary move under it at the next
 * daylight-saving change, silently filing an entry under the wrong Bond-day
 * — the exact failure doc 04 §6 says destroys trust in the streak. The
 * `Etc` and `SystemV` prefixes are the JDK's fixed-offset families, and
 * `UTC` and `GMT` are the same thing without a slash.
 *
 * Deprecated aliases such as `Asia/Calcutta` are accepted and stored exactly
 * as given: the JDK resolves them, and rewriting what a person chose is not
 * this type's job.
 */
@JvmInline
internal value class RegionZone(
    val zone: ZoneId,
) {
    /** The IANA id, as it will be stored — `anchor_timezone` is text, never an offset (doc 04 §6). */
    val id: String get() = zone.id

    companion object {
        /**
         * @throws IllegalArgumentException with the sentence a user reads
         *   through the `422`. It names the rule and never echoes the input
         *   (doc 18 §5) — the message travels into logs.
         */
        fun of(id: String): RegionZone {
            require(id.isRegionZoneId()) { "must be a region time zone such as Europe/London or Africa/Lagos" }
            return RegionZone(ZoneId.of(id))
        }

        /**
         * Membership of the tzdb first, so an id the JDK has never heard of is
         * refused before `ZoneId.of` can throw its own exception, whose message
         * quotes the input.
         */
        private fun String.isRegionZoneId(): Boolean =
            this in ZoneId.getAvailableZoneIds() &&
                contains('/') &&
                !startsWith("Etc/") &&
                !startsWith("SystemV/")
    }
}
