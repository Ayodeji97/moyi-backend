package com.moyi.gratitude.domain

import com.moyi.bond.api.ReminderRoster
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The reminder series' keys (spec C15): nudge 1 keeps the key it had before nudges. */
internal class ReminderKeyTest {
    private val member = UUID.randomUUID()
    private val date = LocalDate.of(2026, 9, 2)

    @Test
    fun `nudge 1's key is the reminder's key from before nudges`() {
        ReminderKey.of(member, date, 1) shouldBe "reminder:$member:$date"
    }

    @Test
    fun `a later nudge's key is the reminder's key with its index`() {
        ReminderKey.of(member, date, 2) shouldBe "reminder:$member:$date:2"
        ReminderKey.of(member, date, 3) shouldBe "reminder:$member:$date:3"
    }

    @Test
    fun `the series is every nudge of the day`() {
        ReminderKey.series(member, date, ReminderRoster.NUDGES_PER_BOND_DAY) shouldContainExactly
            listOf("reminder:$member:$date", "reminder:$member:$date:2", "reminder:$member:$date:3")
    }

    @Test
    fun `a nudge supersedes the ones before it that day, and nudge 1 none`() {
        ReminderKey.before(member, date, 3) shouldContainExactly setOf("reminder:$member:$date", "reminder:$member:$date:2")
        ReminderKey.before(member, date, 1).shouldBeEmpty()
    }

    @Test
    fun `a key is at most 58 characters, inside the ledger's 200`() {
        ReminderKey.of(member, LocalDate.of(2026, 12, 31), 3).length shouldBe 58
    }

    @Test
    fun `there is no nudge 0`() {
        shouldThrow<IllegalArgumentException> { ReminderKey.of(member, date, 0) }
    }
}
