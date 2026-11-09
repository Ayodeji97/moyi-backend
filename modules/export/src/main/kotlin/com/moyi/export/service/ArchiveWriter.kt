package com.moyi.export.service

import com.moyi.bond.api.BondExports
import com.moyi.bond.api.ExportedBond
import com.moyi.export.api.AdminAccessLog
import com.moyi.gratitude.api.ArchiveExports
import com.moyi.gratitude.api.ExportedEntry
import com.moyi.identity.api.AccountExports
import com.moyi.media.api.ExportStorage
import com.moyi.media.api.ExportedMediaFile
import com.moyi.support.api.SupportExports
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.json.JsonMapper
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one person's archive (FR-009, FR-094, T-20, ADR-0059 decision 7) to a zip file:
 *
 * - `moyi-export.json`: the account and its consents, every bond with every day of its archive as
 *   the app shows it to the person, their support messages, and the administrator-access
 *   disclosure (T-10);
 * - `media/<id>.webp` and `media/<id>.ogg`: each photo and voice note the JSON names;
 * - `README.txt`: what the files are.
 *
 * **Every section is the owning module's answer to "what may this person have"**; nothing here
 * reads a table or decides a visibility rule. Streamed: the JSON is written a page of days at a time
 * to a file of its own, so the size of an archive is bounded by the disk, not the heap.
 *
 * Nothing it writes is logged. A failure is thrown and the caller logs its class.
 */
@Component
@Suppress("LongParameterList") // The five modules the archive is gathered from, and the JSON writer.
internal class ArchiveWriter(
    private val accounts: AccountExports,
    private val bonds: BondExports,
    private val archives: ArchiveExports,
    private val support: SupportExports,
    private val storage: ExportStorage,
    private val adminAccess: ObjectProvider<AdminAccessLog>,
    private val json: JsonMapper,
) {
    /** The archive for [userId], written to [zip]. How many media files were named but could no longer be read. */
    fun write(
        exportId: UUID,
        userId: UUID,
        generatedAt: Instant,
        zip: Path,
    ): Int {
        val document = Files.createTempFile("moyi-export-", ".json")
        try {
            val media = mutableListOf<ExportedMediaFile>()
            Files.newOutputStream(document).use { out ->
                json.createGenerator(out).use { generator -> document(generator, exportId, userId, generatedAt, media) }
            }
            return ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                out.putNextEntry(ZipEntry(README))
                out.write(readme(generatedAt).toByteArray(Charsets.UTF_8))
                out.closeEntry()
                out.putNextEntry(ZipEntry(DOCUMENT))
                Files.copy(document, out)
                out.closeEntry()
                media.count { file -> !copy(file, out) }
            }
        } finally {
            Files.deleteIfExists(document)
        }
    }

    private fun document(
        out: JsonGenerator,
        exportId: UUID,
        userId: UUID,
        generatedAt: Instant,
        media: MutableList<ExportedMediaFile>,
    ) {
        val account = checkNotNull(accounts.accountOf(userId)) { "the account no longer exists" }
        out.writeStartObject()
        out.writeStringProperty("format", FORMAT)
        out.writeStringProperty("exportId", exportId.toString())
        out.writePOJOProperty("generatedAt", generatedAt)
        out.writePOJOProperty(
            "account",
            AccountJson(
                id = account.id,
                email = account.email,
                emailVerifiedAt = account.emailVerifiedAt,
                displayName = account.displayName,
                locale = account.locale,
                status = account.status,
                createdAt = account.createdAt,
                updatedAt = account.updatedAt,
            ),
        )
        out.writePOJOProperty("consents", account.consents.map { ConsentJson(it.document, it.version, it.acceptedAt) })
        out.writeName("bonds")
        out.writeStartArray()
        for (bond in bonds.bondsOf(userId)) bond(out, userId, bond, media)
        out.writeEndArray()
        out.writePOJOProperty(
            "supportMessages",
            support.ticketsOf(userId).map { SupportMessageJson(it.id, it.subject, it.body, it.status, it.createdAt, it.closedAt) },
        )
        out.writePOJOProperty("adminAccess", adminAccessOf(userId))
        out.writeEndObject()
    }

    private fun bond(
        out: JsonGenerator,
        userId: UUID,
        bond: ExportedBond,
        media: MutableList<ExportedMediaFile>,
    ) {
        out.writeStartObject()
        out.writePOJOProperty("bond", BondJson.of(bond))
        out.writeName("days")
        out.writeStartArray()
        var before: LocalDate? = null
        do {
            val page = archives.page(userId, bond.id, before)
            for (day in page.days) {
                out.writePOJO(DayJson(day.date, day.status, day.entries.map { entry(userId, it, media) }))
            }
            before = page.next
        } while (before != null)
        out.writeEndArray()
        out.writeEndObject()
    }

    private fun entry(
        userId: UUID,
        entry: ExportedEntry,
        media: MutableList<ExportedMediaFile>,
    ): EntryJson {
        fun file(id: UUID?): String? =
            id?.let { storage.copyOf(userId, it) }?.let { found ->
                media += found
                pathOf(found)
            }
        return EntryJson(
            id = entry.id,
            author = if (entry.mine) "ME" else "PARTNER",
            status = entry.status,
            text = entry.text,
            createdAt = entry.createdAt,
            intendedAt = entry.intendedAt,
            favourited = entry.favourited,
            reaction = entry.reaction,
            image = file(entry.imageId),
            voice = file(entry.voiceId),
        )
    }

    private fun adminAccessOf(userId: UUID): AdminAccessJson {
        val log = adminAccess.getIfAvailable()
        return if (log == null) {
            AdminAccessJson(logKept = false, statement = NO_ADMIN_LOG, accesses = emptyList())
        } else {
            val accesses = log.accessesTo(userId).map { AdminAccessEntryJson(it.at, it.action, it.justification) }
            AdminAccessJson(logKept = true, statement = if (accesses.isEmpty()) NO_ACCESS else ACCESS, accesses = accesses)
        }
    }

    /** False if the object could not be found any more (deleted while the archive was being made). */
    private fun copy(
        file: ExportedMediaFile,
        out: ZipOutputStream,
    ): Boolean {
        val bytes = file.open() ?: return false
        bytes.use {
            out.putNextEntry(ZipEntry(pathOf(file)))
            it.copyTo(NotClosing(out))
            out.closeEntry()
        }
        return true
    }

    private fun pathOf(file: ExportedMediaFile): String = "media/${file.mediaId}.${file.extension}"

    /** So that nothing written into one entry can close the zip under it. */
    private class NotClosing(
        out: OutputStream,
    ) : FilterOutputStream(out) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            out.write(b, off, len)
        }

        override fun close() {
            flush()
        }
    }

    private fun readme(generatedAt: Instant): String =
        """
        |Your Moyi archive, made at $generatedAt (UTC).
        |
        |moyi-export.json holds, in this order:
        |- account: your profile, as Moyi holds it;
        |- consents: each version of the terms, the privacy policy and the age confirmation you accepted, and when;
        |- bonds: every bond you are in or were in, your own settings in it, and every day of its archive as Moyi shows it to you.
        |  Your entries are marked "author": "ME" and your partner's "PARTNER". Your partner's entries are here only once they
        |  were revealed to you. An entry erased after you had read it is here as "status": "DELETED" with no text; that includes
        |  every entry withdrawn when a bond was ended with a block or a leave, yours or your partner's, because there was one copy
        |  of those words and the withdrawal erased it for both of you. "favourited" is your own bookmark; "reaction" is the
        |  reaction on that entry, which you both see;
        |- supportMessages: the messages you sent to Moyi's support; a closed one no longer holds its words;
        |- adminAccess: any access by a Moyi administrator to your account or what you wrote.
        |
        |The media folder holds each photo and voice note the archive names, in the best copy Moyi keeps: photos as WebP, with
        |no location or camera data (Moyi never keeps the file as it was uploaded), and voice notes as Ogg Opus.
        |
        |Times are UTC, in ISO 8601. The download link for this archive lasted 24 hours; after that the archive was deleted.
        |
        """.trimMargin()

    private companion object {
        const val FORMAT = "moyi-export/1"
        const val DOCUMENT = "moyi-export.json"
        const val README = "README.txt"

        const val NO_ADMIN_LOG =
            "No administrator access to your account or to what you wrote is recorded. Moyi has no administrator " +
                "console yet; once it has, every access it allows is recorded and listed here."
        const val NO_ACCESS = "No administrator access to your account or to what you wrote is recorded."
        const val ACCESS = "Every recorded administrator access to your account or to what you wrote, oldest first."
    }
}
