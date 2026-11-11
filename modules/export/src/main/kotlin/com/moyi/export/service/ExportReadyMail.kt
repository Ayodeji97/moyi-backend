package com.moyi.export.service

import com.moyi.identity.api.AccountMessage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * FR-085's "data export ready" email (ADR-0059 decision 10). **It carries no link**: whoever reads
 * the mailbox would otherwise hold the archive for a day, and a signed URL needs no sign-in. It
 * tells the person where in the app to download it, and until when. Keyed by the export's id, so a
 * job that sends it twice sends it once.
 */
internal object ExportReadyMail {
    const val SUBJECT = "Your Moyi archive is ready"

    fun of(
        exportId: UUID,
        expiresAt: Instant,
    ): AccountMessage {
        val until = UNTIL.format(expiresAt)
        val paragraphs =
            listOf(
                "The copy of your Moyi archive you asked for is ready.",
                "To download it, open Moyi and go to your account, then Export your archive. It can be downloaded " +
                    "until $until; after that it is deleted, and you can ask for a new one.",
                "There is no link in this email, so that only someone signed in to your account can download it.",
                "If you did not ask for this, change your password, and sign out of the devices you do not recognise.",
            )
        return AccountMessage(
            subject = SUBJECT,
            text = "Hello,\n\n" + paragraphs.joinToString("\n\n"),
            html = "<p>Hello,</p>" + paragraphs.joinToString("") { "<p>$it</p>" },
            idempotencyKey = "export-ready-$exportId",
        )
    }

    private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.UK).withZone(ZoneOffset.UTC)
}
