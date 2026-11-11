package com.moyi.export.infra

import com.moyi.bond.api.BondExports
import com.moyi.bond.api.ExportedBond
import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.ArchiveExports
import com.moyi.gratitude.api.ExportedDay
import com.moyi.gratitude.api.ExportedDays
import com.moyi.identity.api.AccountExports
import com.moyi.identity.api.AccountMail
import com.moyi.identity.api.AccountMailOutcome
import com.moyi.identity.api.AccountMessage
import com.moyi.identity.api.ExportedAccount
import com.moyi.media.api.ExportStorage
import com.moyi.media.api.ExportedMediaFile
import com.moyi.support.api.ExportedTicket
import com.moyi.support.api.SupportExports
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A Spring context just large enough to test this module: `common` and `export`, and each of the
 * five modules it gathers from only through its `api` port, supplied here as a fake, so a test here
 * cannot depend on their internals any more than the production code can. What each module puts in
 * an archive is tested in that module (`ArchiveExportsTest`, `BondExportsTest`, `AccountExportsTest`,
 * `SupportExportsTest`, `ExportStorageTest`).
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common", "com.moyi.export"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.export")
internal class ExportTestApplication {
    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }

    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(start = START)

    @Bean
    fun people(): People = People()

    @Bean
    fun storage(): FakeStorage = FakeStorage()

    @Bean
    fun mail(): RecordingMail = RecordingMail()

    companion object {
        val START: Instant = Instant.parse("2026-11-09T10:00:00Z")
    }
}

/** identity, bond, gratitude and support, as one table of what each person has. */
internal class People :
    AccountExports,
    BondExports,
    ArchiveExports,
    SupportExports {
    val accounts = ConcurrentHashMap<UUID, ExportedAccount>()
    val bonds = ConcurrentHashMap<UUID, List<ExportedBond>>()
    val days = ConcurrentHashMap<Pair<UUID, UUID>, List<ExportedDay>>()
    val tickets = ConcurrentHashMap<UUID, List<ExportedTicket>>()
    val members = ConcurrentHashMap<UUID, Set<UUID>>()

    /** Run during a build, as the support messages are read, before the archive is stored: what a concurrent change does. */
    @Volatile
    var duringBuild: () -> Unit = {}

    override fun accountOf(userId: UUID): ExportedAccount? = accounts[userId]

    override fun bondsOf(userId: UUID): List<ExportedBond> = bonds[userId].orEmpty()

    override fun accountsInBond(bondId: UUID): Set<UUID> = members[bondId].orEmpty()

    override fun page(
        userId: UUID,
        bondId: UUID,
        before: LocalDate?,
    ): ExportedDays {
        // Two days a page, newest first, as the real one pages: the writer must follow `next`.
        val all = days[userId to bondId].orEmpty().sortedByDescending { it.date }.filter { before == null || it.date < before }
        val page = all.take(2)
        return ExportedDays(page, if (all.size > 2) page.last().date else null)
    }

    override fun ticketsOf(userId: UUID): List<ExportedTicket> {
        duringBuild()
        return tickets[userId].orEmpty()
    }

    fun clear() {
        accounts.clear()
        bonds.clear()
        days.clear()
        tickets.clear()
        members.clear()
        duringBuild = {}
    }
}

/** media's storage in memory: media objects any viewer may copy, and archives by key. */
internal class FakeStorage : ExportStorage {
    val media = ConcurrentHashMap<UUID, Pair<UUID, ByteArray>>()
    val archives = ConcurrentHashMap<String, ByteArray>()
    val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var failing = false

    /** Thrown by [storeArchive] when set: a checked exception, as a full disk or a cut-off stream is. */
    @Volatile
    var failingWith: Exception? = null

    /** Makes [deleteArchive] throw when set, deleting nothing: the storage unreachable when an archive is to go. */
    @Volatile
    var failingDeletes = false

    /** Media whose bytes cannot be read: the stream throws an `IOException` part-way, as a storage cut off does. */
    val unreadable: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun copyOf(
        viewer: UUID,
        mediaId: UUID,
    ): ExportedMediaFile? {
        val owner = media[mediaId]?.first
        return if (owner == viewer || owner == SHARED) {
            ExportedMediaFile(mediaId, "IMAGE", "webp", "image/webp") {
                if (mediaId in unreadable) BrokenStream() else media[mediaId]?.second?.inputStream()
            }
        } else {
            null
        }
    }

    override fun storeArchive(file: Path): String {
        check(!failing) { "the storage is down (made to by the test)" }
        failingWith?.let { throw it }
        val key = "x/${UUID.randomUUID()}.zip"
        archives[key] = Files.readAllBytes(file)
        return key
    }

    override fun archiveUrl(
        key: String,
        signedAt: Instant,
        expiresIn: Duration,
    ): URI = URI.create("https://storage.test/$key?signed=${signedAt.epochSecond}&expires=${expiresIn.seconds}")

    override fun deleteArchive(key: String) {
        check(!failingDeletes) { "the storage is down (made to by the test)" }
        archives.remove(key)
        deleted += key
    }

    fun clear() {
        media.clear()
        archives.clear()
        deleted.clear()
        failing = false
        failingWith = null
        failingDeletes = false
        unreadable.clear()
    }

    companion object {
        /** An owner id that marks a photo any viewer may have: a partner's, passed. */
        val SHARED: UUID = UUID(0, 1)
    }
}

/** A stream that fails on its first read, as a storage connection cut off does. */
internal class BrokenStream : java.io.InputStream() {
    override fun read(): Int = throw java.io.IOException("the connection was reset (made to by the test)")
}

/** identity's port, recording what it was asked to send and to whom. */
internal class RecordingMail : AccountMail {
    val sent: MutableList<Pair<UUID, AccountMessage>> = Collections.synchronizedList(mutableListOf())

    /** Makes a send throw when set, recording nothing: the mail provider's client failing outright. */
    @Volatile
    var failing = false

    override fun sendToAccountHolder(
        userId: UUID,
        message: AccountMessage,
    ): AccountMailOutcome {
        check(!failing) { "the mail provider is down (made to by the test)" }
        sent.add(userId to message)
        return AccountMailOutcome.ACCEPTED
    }
}
