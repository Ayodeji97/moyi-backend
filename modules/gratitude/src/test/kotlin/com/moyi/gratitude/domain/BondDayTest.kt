package com.moyi.gratitude.domain

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal class BondDayTest {
    private val dayId = BondDayId(UUID.randomUUID())
    private val bondId = UUID.randomUUID()
    private val date = LocalDate.of(2026, 9, 15)
    private val lagos = ZoneId.of("Africa/Lagos")
    private val now = Instant.parse("2026-09-15T08:00:00Z")
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val text = EntryText.of("thank you for the coffee")

    @Test
    fun `a day opens with no entries, in the zone it was opened in`() {
        val day = BondDay.open(dayId, bondId, LocalDate.of(2026, 9, 15), ZoneId.of("Africa/Lagos"), now)

        day.status shouldBe BondDayStatus.OPEN
        day.entryCount shouldBe 0
        day.anchorTimezone shouldBe ZoneId.of("Africa/Lagos")
        day.isClosed shouldBe false
    }

    @Test
    fun `the first entry makes it partial and the second does not reveal it yet`() {
        // C1 has no reveal — that is C2, with the row lock and the race test.
        // The day is left honest about its count and wrong about nothing else.
        val partial = BondDay.open(dayId, bondId, date, lagos, now).withEntry()
        partial.status shouldBe BondDayStatus.PARTIAL
        partial.entryCount shouldBe 1

        val both = partial.withEntry()
        both.entryCount shouldBe 2
        both.status shouldBe BondDayStatus.PARTIAL
    }

    @Test
    fun `a day opened while the bond waits for its second member is suspended`() {
        // Doc 04 §8.3a, as the Phase 3 design §12.4 resolves it: the row must
        // exist because the entry hangs off it, and J1 guarantees the creator
        // writes before the invitee joins. SUSPENDED is §8.1's own mechanism —
        // the close job leaves it alone and the streak walk skips it.
        val day = BondDay.openSuspended(dayId, bondId, date, lagos, now)

        day.status shouldBe BondDayStatus.SUSPENDED
        day.withEntry().status shouldBe BondDayStatus.SUSPENDED
    }

    @Test
    fun `an author always reads their own entry, and nobody reads a locked one`() {
        // BR-1's three clauses. In C1 only the first can be true.
        val day = BondDay.open(dayId, bondId, date, lagos, now).withEntry()
        val mine = Entry.submit(EntryId(UUID.randomUUID()), day.id, bondId, ada, text, now, now)

        mine.canBeReadBy(ada, day) shouldBe true
        mine.canBeReadBy(bea, day) shouldBe false
    }

    @Test
    fun `the eight statuses doc 04 defines all exist, whatever this slice produces`() {
        BondDayStatus.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("OPEN", "PARTIAL", "PENDING_REVEAL", "REVEALED", "SOLO", "EMPTY", "SUSPENDED", "FROZEN")
    }

    @Test
    fun `an entry never prints its own words`() {
        // Doc 18 §5/§9, mirroring EntryTextTest's own regression: EntryText
        // already redacts itself, and a Kotlin value class dispatches its
        // `toString` statically even boxed inside Entry's generated one — but
        // that holds only because no *other* field on Entry is personal data.
        // This is the tripwire for the day somebody adds one.
        val day = BondDay.open(dayId, bondId, date, lagos, now).withEntry()
        val mine = Entry.submit(EntryId(UUID.randomUUID()), day.id, bondId, ada, text, now, now)

        "$mine" shouldNotContain "thank you for the coffee"
        mine.toString() shouldContain "EntryText(redacted)"
    }
}
