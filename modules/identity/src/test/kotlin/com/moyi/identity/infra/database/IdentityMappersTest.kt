package com.moyi.identity.infra.database

import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHashAlgorithm
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class IdentityMappersTest {
    @Test
    fun `a user survives the round trip with every field intact`() {
        // Field-by-field equality via the data class, so a column added to one
        // side and forgotten on the other fails here rather than by silently
        // reading back a null in production.
        val original = user()

        original.toEntity().toDomain() shouldBe original
    }

    @Test
    fun `a user with every optional field populated also survives`() {
        val original =
            user().copy(
                emailVerifiedAt = NOW,
                avatarMediaId = UUID.fromString("00000000-0000-4000-8000-0000000000aa"),
                updatedAt = NOW.plusSeconds(1),
                deletedAt = NOW.plusSeconds(2),
                tokensInvalidBefore = null,
                status = UserStatus.DELETED,
            )

        original.toEntity().toDomain() shouldBe original
    }

    @Test
    fun `credentials survive the round trip`() {
        val original = credentials()

        original.toEntity().toDomain() shouldBe original
    }

    @Test
    fun `a freshly mapped entity claims to be new, which is what makes save insert`() {
        user().toEntity().isNew() shouldBe true
        credentials().toEntity().isNew() shouldBe true
    }

    @Test
    fun `applyTo carries changes onto an existing row`() {
        val entity = user().toEntity()
        val changed =
            user().copy(
                displayName = "Ada Lovelace",
                locale = "fr",
                status = UserStatus.SUSPENDED,
                emailVerifiedAt = NOW,
                updatedAt = NOW.plusSeconds(5),
            )

        changed.applyTo(entity)

        entity.toDomain() shouldBe changed
    }

    @Test
    fun `applyTo leaves createdAt alone`() {
        // `created_at` is `updatable = false` in the schema, so copying a
        // changed value here would produce an entity that disagrees with the
        // row it represents — and Hibernate would never write the difference.
        val entity = user().toEntity()

        user().copy(createdAt = NOW.plusSeconds(3600)).applyTo(entity)

        entity.toDomain().createdAt shouldBe NOW
    }

    @Test
    fun `applyTo refuses to write one user onto another user's row`() {
        // Without this, a mixed-up id in a service method updates the wrong
        // person's account and every column except the id looks correct.
        val entity = user().toEntity()
        val someoneElse = user().copy(id = UserId(UUID.fromString("00000000-0000-7000-8000-000000000002")))

        shouldThrow<IllegalArgumentException> { someoneElse.applyTo(entity) }
        shouldThrow<IllegalArgumentException> {
            credentials()
                .copy(userId = UserId(UUID.fromString("00000000-0000-7000-8000-000000000002")))
                .applyTo(credentials().toEntity())
        }
    }

    @Test
    fun `applyTo carries credential changes`() {
        val entity = credentials().toEntity()
        val changed =
            credentials().copy(
                passwordHash = PasswordHash("\$argon2id\$v=19\$m=19456,t=2,p=1\$bmV3c2FsdA\$bmV3"),
                passwordUpdatedAt = NOW.plusSeconds(10),
                failedAttempts = 3,
                lockedUntil = NOW.plusSeconds(900),
            )

        changed.applyTo(entity)

        entity.toDomain() shouldBe changed
    }

    @Test
    fun `entities are equal by id, not by contents`() {
        // Identity, not state: two loads of the same row are the same user
        // even if one is stale, and two different users are never equal
        // because they happen to share a display name.
        val entity = user().toEntity()
        val sameRowDifferentState = user().copy(displayName = "Someone else").toEntity()
        val differentRow = user().copy(id = UserId(UUID.fromString("00000000-0000-7000-8000-000000000002"))).toEntity()

        (entity == sameRowDifferentState) shouldBe true
        entity.hashCode() shouldBe sameRowDifferentState.hashCode()
        (entity == differentRow) shouldBe false
        (entity == user()) shouldBe false
    }

    @Test
    fun `an entity does not print anything personal`() {
        // Entities end up in log lines and exception messages by accident.
        // The id and the status are operationally useful; the address and the
        // name are the user's.
        val printed = user().toEntity().toString()

        printed shouldNotContain "ada@example.com"
        printed shouldNotContain "Ada"

        val credentialsPrinted = credentials().toEntity().toString()
        credentialsPrinted shouldNotContain ARGON2ID_HASH
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T10:15:30Z")
        val USER_ID: UUID = UUID.fromString("00000000-0000-7000-8000-000000000001")
        const val ARGON2ID_HASH = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaA"

        fun user() =
            User(
                id = UserId(USER_ID),
                email = Email("ada@example.com"),
                emailVerifiedAt = null,
                displayName = "Ada",
                avatarMediaId = null,
                locale = "en",
                status = UserStatus.PENDING_VERIFICATION,
                createdAt = NOW,
                updatedAt = null,
                deletedAt = null,
                tokensInvalidBefore = null,
            )

        fun credentials() =
            Credentials(
                userId = UserId(USER_ID),
                passwordHash = PasswordHash(ARGON2ID_HASH),
                algorithm = PasswordHashAlgorithm.ARGON2ID,
                passwordUpdatedAt = NOW,
                failedAttempts = 0,
                lockedUntil = null,
            )
    }
}
