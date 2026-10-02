package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.EntryStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [BondDayEntity] and [EntryEntity] carry a `Persistable`-based identity —
 * equal, and the same hash, purely by id — for the reason `BondEntity`'s own
 * KDoc gives: Hibernate's own dirty-checking and first-level cache rely on
 * that, and a `data class`'s generated `equals` (every field) would disagree
 * with it. No Spring context, no database: both types are plain objects the
 * moment nothing but their own constructor and methods are on trial.
 */
internal class EntityIdentityTest {
    private val id = UUID.randomUUID()
    private val now = Instant.parse("2026-09-28T20:00:00Z")

    @Test
    fun `two bond-day entities are equal, and hash the same, by id alone`() {
        val one = bondDayEntity(id, entryCount = 0)
        val two = bondDayEntity(id, entryCount = 2)
        val other = bondDayEntity(UUID.randomUUID())

        one shouldBe two
        one.hashCode() shouldBe two.hashCode()
        (one == other) shouldBe false
    }

    @Test
    fun `a bond-day's toString names its id, bond and status, and nothing about its counts or timestamps`() {
        val entity = bondDayEntity(id, entryCount = 2)

        entity.toString() shouldContain id.toString()
        entity.toString() shouldContain "OPEN"
        // now's exact ISO instant is distinctive enough that its absence is
        // meaningful — unlike the raw entryCount digit, which a random UUID
        // could contain by coincidence and make this assertion flaky.
        entity.toString() shouldNotContain now.toString()
    }

    @Test
    fun `two entry entities are equal, and hash the same, by id alone`() {
        val one = entryEntity(id, text = "thank you for the coffee")
        val two = entryEntity(id, text = "something entirely different")
        val other = entryEntity(UUID.randomUUID())

        one shouldBe two
        one.hashCode() shouldBe two.hashCode()
        (one == other) shouldBe false
    }

    @Test
    fun `an entry's toString never prints its text`() {
        // Doc 18 §5/§9 — the same leak EntryText's own redacted toString guards against.
        val entity = entryEntity(id, text = "a secret between two people")

        entity.toString() shouldNotContain "a secret between two people"
        entity.toString() shouldContain id.toString()
    }

    private fun bondDayEntity(
        entityId: UUID,
        entryCount: Short = 0,
    ): BondDayEntity =
        BondDayEntity(
            id = entityId,
            bondId = UUID.randomUUID(),
            date = LocalDate.of(2026, 9, 28),
            status = BondDayStatus.OPEN,
            anchorTimezone = "Africa/Lagos",
            startsAt = Instant.parse("2026-09-27T23:00:00Z"),
            endsAt = Instant.parse("2026-09-28T23:00:00Z"),
            entryCount = entryCount,
            revealedAt = null,
            closedAt = null,
            createdAt = now,
            version = 0,
        )

    private fun entryEntity(
        entityId: UUID,
        text: String = "thank you",
    ): EntryEntity =
        EntryEntity(
            id = entityId,
            bondDayId = UUID.randomUUID(),
            bondId = UUID.randomUUID(),
            authorMemberId = UUID.randomUUID(),
            text = text,
            imageMediaId = null,
            voiceMediaId = null,
            voiceDurationMs = null,
            promptId = null,
            status = EntryStatus.SUBMITTED,
            authorDeletedAccount = false,
            createdAt = now,
            intendedAt = now,
            updatedAt = now,
            revealedAt = null,
            deletedAt = null,
        )
}
