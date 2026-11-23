package com.moyi.notification.api

import com.moyi.notification.domain.PushBudget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The value rules of the push API: the one place a token's shape and a dedupe key's length are decided. */
internal class PushApiTest {
    @Test
    fun `a push token never prints itself, nor does a message or a registration that holds one`() {
        val token = PushToken("fcm-token-secret")
        token.toString() shouldBe "PushToken(<redacted>)"
        PushMessage(token, PushCategory.REVEAL, UUID.randomUUID(), Instant.EPOCH, null).toString() shouldNotContain "fcm-token-secret"
        PushTokenRegistration(UUID.randomUUID(), UUID.randomUUID(), PushPlatform.ANDROID, token).toString() shouldNotContain
            "fcm-token-secret"
    }

    @Test
    fun `a push token is 1 to 1024 characters with no whitespace`() {
        PushToken("a".repeat(PushToken.MAX_LENGTH)).value.length shouldBe PushToken.MAX_LENGTH
        shouldThrow<IllegalArgumentException> { PushToken("") }
        shouldThrow<IllegalArgumentException> { PushToken("a".repeat(PushToken.MAX_LENGTH + 1)) }
        shouldThrow<IllegalArgumentException> { PushToken("two words") }
        shouldThrow<IllegalArgumentException> { PushToken("line\nbreak") }
    }

    @Test
    fun `a dedupe key is 1 to 200 characters`() {
        val user = UUID.randomUUID()
        val bond = UUID.randomUUID()
        val at = Instant.parse("2026-10-28T00:00:00Z")
        PushRequest(user, bond, PushCategory.MILESTONE, "k".repeat(PushRequest.MAX_DEDUPE_KEY_LENGTH), at, RECIPIENT, null, null, null)
        shouldThrow<IllegalArgumentException> { PushRequest(user, bond, PushCategory.MILESTONE, "", at, RECIPIENT, null, null, null) }
        shouldThrow<IllegalArgumentException> {
            val tooLong = "k".repeat(PushRequest.MAX_DEDUPE_KEY_LENGTH + 1)
            PushRequest(user, bond, PushCategory.MILESTONE, tooLong, at, RECIPIENT, null, null, null)
        }
    }

    @Test
    fun `a nudge on any category but REMINDER is refused`() {
        val at = Instant.parse("2026-11-16T00:00:00Z")
        shouldThrow<IllegalArgumentException> {
            PushRequest(UUID.randomUUID(), UUID.randomUUID(), PushCategory.REVEAL, "k", at, RECIPIENT, null, Nudge(1, 0, emptySet()), null)
        }
        PushRequest(UUID.randomUUID(), UUID.randomUUID(), PushCategory.REMINDER, "k", at, RECIPIENT, null, Nudge(1, 0, emptySet()), null)
    }

    @Test
    fun `a shared change is required on the category and refused on any other`() {
        val at = Instant.parse("2026-11-16T00:00:00Z")

        fun ask(
            category: PushCategory,
            change: SharedChange?,
        ) = PushRequest(UUID.randomUUID(), UUID.randomUUID(), category, "k", at, RECIPIENT, null, null, change)
        ask(PushCategory.SHARED_SETTING_CHANGED, SharedChange.SETTING)
        shouldThrow<IllegalArgumentException> { ask(PushCategory.SHARED_SETTING_CHANGED, null) }
        shouldThrow<IllegalArgumentException> { ask(PushCategory.REVEAL, SharedChange.SETTING) }
        shouldThrow<IllegalArgumentException> { ask(PushCategory.PROPOSAL_OPENED, SharedChange.PROPOSAL_ACCEPTED) }
    }

    @Test
    fun `SEVERAL cannot be asked for`() {
        val at = Instant.parse("2026-11-16T00:00:00Z")
        shouldThrow<IllegalArgumentException> {
            PushRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PushCategory.SHARED_SETTING_CHANGED,
                "k",
                at,
                RECIPIENT,
                null,
                null,
                SharedChange.SEVERAL,
            )
        }
    }

    @Test
    fun `the sharedSettings toggle governs the category and is on by default`() {
        NotificationPreferences.DEFAULTS.sharedSettings shouldBe true
        NotificationPreferences.DEFAULTS.allows(PushCategory.SHARED_SETTING_CHANGED) shouldBe true
        NotificationPreferences(sharedSettings = false).allows(PushCategory.SHARED_SETTING_CHANGED) shouldBe false
        PushCategory.SHARED_SETTING_CHANGED.isRevealClass shouldBe false
    }

    @Test
    fun `the nudge's cross-Bond cap is the budget's`() {
        Nudge.ACROSS_BONDS shouldBe PushBudget.ACROSS_BONDS
    }

    @Test
    fun `a nudge's leave-free is 0 to 5`() {
        Nudge(2, 5, emptySet())
        shouldThrow<IllegalArgumentException> { Nudge(2, 6, emptySet()) }
        shouldThrow<IllegalArgumentException> { Nudge(2, -1, emptySet()) }
        shouldThrow<IllegalArgumentException> { Nudge(0, 0, emptySet()) }
        shouldThrow<IllegalArgumentException> { Nudge(1, 0, setOf("")) }
        shouldThrow<IllegalArgumentException> { Nudge(1, 0, setOf("k".repeat(201))) }
    }

    @Test
    fun `a member who never chose has every category on except on-this-day`() {
        val defaults = NotificationPreferences.DEFAULTS
        PushCategory.entries.filter(defaults::allows).toSet() shouldBe PushCategory.entries.toSet() - PushCategory.ON_THIS_DAY
    }

    @Test
    fun `each toggle governs its own categories and no other`() {
        // A day opening with one entry is the reveal's news, so the reveal toggle governs it too.
        val governs =
            mapOf(
                NotificationPreferences(reminder = false) to setOf(PushCategory.REMINDER),
                NotificationPreferences(partnerJoined = false) to setOf(PushCategory.PARTNER_JOINED),
                NotificationPreferences(reveal = false) to setOf(PushCategory.REVEAL, PushCategory.SOLO_UNLOCK),
                NotificationPreferences(milestone = false) to setOf(PushCategory.MILESTONE),
                NotificationPreferences(proposal = false) to setOf(PushCategory.PROPOSAL_OPENED),
                NotificationPreferences(sharedSettings = false) to setOf(PushCategory.SHARED_SETTING_CHANGED),
                NotificationPreferences(onThisDay = true) to emptySet(),
            )
        governs.forEach { (preferences, off) ->
            val expectedOff = off + if (preferences.onThisDay) emptySet() else setOf(PushCategory.ON_THIS_DAY)
            PushCategory.entries.filterNot(preferences::allows).toSet() shouldBe expectedOff
        }
    }

    @Test
    fun `reveal-class is the reveal and the day that opened with one entry, and nothing else`() {
        PushCategory.entries.filter { it.isRevealClass }.toSet() shouldBe setOf(PushCategory.REVEAL, PushCategory.SOLO_UNLOCK)
    }

    private companion object {
        val RECIPIENT = PushRecipient(ZoneId.of("Europe/London"), null, NotificationPreferences.DEFAULTS)
    }
}
