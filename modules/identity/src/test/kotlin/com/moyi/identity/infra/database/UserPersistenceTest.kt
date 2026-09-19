package com.moyi.identity.infra.database

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHashAlgorithm
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import javax.sql.DataSource

/**
 * The boundary tests for identity persistence: real Postgres, real Flyway,
 * real Hibernate (doc 12: "prefer real-Postgres integration tests over
 * mocks"). Three things are on trial here that a unit test cannot reach —
 * the constraints in the migration, the statements Hibernate actually emits,
 * and whether the entity mapping agrees with the schema at all.
 *
 * That last one needs no test of its own: `ddl-auto: validate` in this
 * module's test `application.yml` means the context below does not start
 * unless every mapped column exists with a compatible type.
 */
@SpringBootTest
internal class UserPersistenceTest(
    @Autowired private val users: UserRepository,
    @Autowired private val credentials: CredentialsRepository,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired entityManagerFactory: EntityManagerFactory,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()

    @AfterEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE consent_records, credentials, users CASCADE")
    }

    @Test
    fun `inserting a user with an assigned id costs exactly one statement`() {
        // The reason UserEntity implements Persistable. Without it, save()
        // asks merge() to look for a row that cannot exist, and every
        // registration pays for a SELECT that always misses. Nothing fails;
        // the bill just doubles, invisibly, on the busiest write path in the
        // system.
        val user = newUser()
        statistics.clear()

        transactions.executeWithoutResult { users.save(user.toEntity()) }

        statistics.prepareStatementCount shouldBe 1
    }

    @Test
    fun `a user survives a real round trip through Postgres`() {
        val original =
            newUser().copy(
                emailVerifiedAt = NOW,
                avatarMediaId = ids.opaque(),
                locale = "fr",
                status = UserStatus.ACTIVE,
                updatedAt = NOW.plusSeconds(60),
            )

        transactions.executeWithoutResult { users.save(original.toEntity()) }
        val loaded = transactions.execute { users.findById(original.id.value).orElse(null) }

        loaded.shouldNotBeNull().toDomain() shouldBe original
    }

    @Test
    fun `two accounts cannot share a mailbox, whatever the casing`() {
        // The whole account model rests on this. If the constraint were
        // case-SENSITIVE — which it would be on a plain `text` column —
        // Ada@example.com and ada@example.com become two accounts reachable
        // from one inbox, and password reset becomes an account-takeover.
        transactions.executeWithoutResult { users.save(newUser(email = "ada@example.com").toEntity()) }

        shouldThrow<DataIntegrityViolationException> {
            transactions.executeWithoutResult { users.save(newUser(email = "ADA@Example.COM").toEntity()) }
        }
    }

    @Test
    fun `findByEmail matches regardless of case, without lowercasing anything`() {
        val stored = newUser(email = "Ada.Lovelace@Example.com")
        transactions.executeWithoutResult { users.save(stored.toEntity()) }

        val found = transactions.execute { users.findByEmail("ada.lovelace@example.COM") }

        found.shouldNotBeNull().getId() shouldBe stored.id.value
        // And the address is stored as the user typed it, not folded.
        found.email shouldBe "Ada.Lovelace@Example.com"
    }

    @Test
    fun `the constraint names the code reacts to are the ones the database has`() {
        // `RegisterUser` decides whether a failure was "already registered" by
        // comparing the violated constraint's name against a constant. That
        // constant is a string, and a string that disagrees with the schema
        // fails in the worst possible direction: a duplicate registration
        // stops being absorbed and becomes a 500, on the sign-up path, in
        // production, with every test still green.
        val declared =
            jdbc.queryForList(
                """
                SELECT conname FROM pg_constraint
                WHERE conrelid IN ('users'::regclass, 'consent_records'::regclass)
                """.trimIndent(),
                String::class.java,
            )

        declared shouldContainAll
            listOf(
                IdentityConstraints.USERS_EMAIL_UNIQUE,
                IdentityConstraints.CONSENT_RECORD_UNIQUE,
            )
    }

    @Test
    fun `the email lookup uses the unique index rather than scanning the table`() {
        // Doc 18 §6 question 7, and doc 18 §10: every query in a request path
        // has been EXPLAINed at least once. This one is the login path, so it
        // runs on every sign-in attempt and every registration.
        //
        // It also guards something specific to `citext`: a comparison that
        // needs a cast on the *column* side cannot use the index at all, and
        // the only symptom is a sequential scan that stays fast until the
        // table is large. Stats are needed for the planner to have an
        // opinion worth reading, hence the rows and the ANALYZE.
        jdbc.update(
            """
            INSERT INTO users (id, email, display_name, locale, status, created_at)
            SELECT gen_random_uuid(), 'user' || i || '@example.com', 'User ' || i, 'en', 'ACTIVE', now()
            FROM generate_series(1, 2000) AS i
            """.trimIndent(),
        )
        jdbc.execute("ANALYZE users")

        val plan =
            jdbc
                .queryForList(
                    "EXPLAIN SELECT id FROM users WHERE email = 'user1234@example.com'::citext",
                    String::class.java,
                ).joinToString("\n")

        plan.contains("Index Scan") shouldBe true
        plan.contains("users_email_key") shouldBe true
    }

    @Test
    fun `changing a loaded user updates the row instead of inserting a second one`() {
        // The trap on the other side of Persistable: an entity that came from
        // the database must say it is not new, or the update path turns into
        // a duplicate-key error on the next save.
        val original = newUser()
        transactions.executeWithoutResult { users.save(original.toEntity()) }

        transactions.executeWithoutResult {
            val entity = users.findById(original.id.value).orElseThrow()
            entity.isNew() shouldBe false
            original.copy(displayName = "Ada Lovelace", updatedAt = NOW).applyTo(entity)
        }

        users.count() shouldBe 1
        transactions.execute { users.findById(original.id.value).orElseThrow().displayName } shouldBe "Ada Lovelace"
    }

    @Test
    fun `the database rejects a status the application does not know`() {
        // The CHECK constraint is what stops an unmappable row existing at
        // all. Written through raw JDBC because the enum makes it unreachable
        // from Kotlin — which is exactly why the database needs its own
        // opinion: the next writer might be a migration or a console session.
        val failure =
            shouldThrow<DataIntegrityViolationException> {
                jdbc.update(
                    """
                    INSERT INTO users (id, email, display_name, locale, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    ids.timeOrdered(),
                    "someone@example.com",
                    "Someone",
                    "en",
                    "PROBATIONARY",
                    java.sql.Timestamp.from(NOW),
                )
            }

        failure.message.orEmpty().contains("users_status_check") shouldBe true
    }

    @Test
    fun `the database enforces the display name limit too`() {
        val failure =
            shouldThrow<DataIntegrityViolationException> {
                transactions.executeWithoutResult {
                    jdbc.update(
                        """
                        INSERT INTO users (id, email, display_name, locale, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        ids.timeOrdered(),
                        "someone@example.com",
                        "a".repeat(User.MAX_DISPLAY_NAME_LENGTH + 1),
                        "en",
                        UserStatus.ACTIVE.name,
                        java.sql.Timestamp.from(NOW),
                    )
                }
            }

        failure.message.orEmpty().contains("users_display_name_length_check") shouldBe true
    }

    @Test
    fun `credentials are stored against the user and go when the user goes`() {
        val user = newUser()
        transactions.executeWithoutResult {
            users.save(user.toEntity())
            credentials.save(newCredentials(user.id).toEntity())
        }

        val stored = transactions.execute { credentials.findById(user.id.value) }.shouldNotBeNull()
        stored.toDomain().failedAttempts shouldBe 0

        transactions.executeWithoutResult { users.deleteById(user.id.value) }

        // ON DELETE CASCADE, not application code: a password hash outliving
        // the account it belonged to is a privacy incident waiting for a
        // forgotten cleanup job.
        jdbc.queryForObject("SELECT count(*) FROM credentials", Int::class.java) shouldBe 0
    }

    @Test
    fun `credentials also insert in a single statement`() {
        val user = newUser()
        transactions.executeWithoutResult { users.save(user.toEntity()) }
        statistics.clear()

        transactions.executeWithoutResult { credentials.save(newCredentials(user.id).toEntity()) }

        statistics.prepareStatementCount shouldBe 1
    }

    private fun newUser(email: String = "ada@example.com") =
        User(
            id = UserId(ids.timeOrdered()),
            email = Email(email),
            emailVerifiedAt = null,
            displayName = "Ada",
            avatarMediaId = null,
            locale = "en",
            status = UserStatus.PENDING_VERIFICATION,
            createdAt = NOW,
            updatedAt = null,
            deletedAt = null,
        )

    private fun newCredentials(userId: UserId) =
        Credentials(
            userId = userId,
            passwordHash = PasswordHash("\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaA"),
            algorithm = PasswordHashAlgorithm.ARGON2ID,
            passwordUpdatedAt = NOW,
            failedAttempts = 0,
            lockedUntil = null,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T10:15:30Z")
    }
}
