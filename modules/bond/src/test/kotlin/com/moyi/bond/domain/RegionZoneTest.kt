package com.moyi.bond.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * ADR-0004's anchor zone and doc 04 §6's member zone are both this type.
 * The interesting half is what it *refuses*: a fixed offset carries no
 * daylight-saving rules, so a bond anchored on one would see "our day" move
 * under it twice a year.
 */
internal class RegionZoneTest {
    @ParameterizedTest
    @ValueSource(strings = ["Africa/Lagos", "Europe/London", "Asia/Kathmandu", "Pacific/Chatham", "America/Sao_Paulo", "Asia/Calcutta"])
    fun `a region id is accepted and kept exactly as given`(id: String) {
        // Asia/Calcutta is a deprecated alias the JDK still resolves. Kept as
        // typed rather than rewritten to Asia/Kolkata: what a person chose is
        // not this type's to edit.
        RegionZone.of(id).id shouldBe id
    }

    @ParameterizedTest
    @ValueSource(strings = ["UTC", "GMT", "Etc/GMT+3", "Etc/UTC", "SystemV/EST5", "+01:00", "Mars/Olympus", "", "   "])
    fun `anything that is not a region id is refused with the domain's own sentence`(id: String) {
        val refused = shouldThrow<IllegalArgumentException> { RegionZone.of(id) }

        // The message is what a user reads through the 422, so it names the
        // rule and never echoes the input (doc 18 §5).
        refused.message shouldBe "must be a region time zone such as Europe/London or Africa/Lagos"
    }
}
