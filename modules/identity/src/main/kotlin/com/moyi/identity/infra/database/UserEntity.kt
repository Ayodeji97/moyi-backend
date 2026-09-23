package com.moyi.identity.infra.database

import com.moyi.identity.domain.UserStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.Type
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * The `users` row. A persistence detail, kept out of the domain on purpose
 * (doc 25 §6 and the Konsist rule that pins `@Entity` to `infra`).
 *
 * **Why this implements [Persistable].** Our ids are assigned in application
 * code, not by the database (doc 06 §1). `JpaRepository.save()` chooses
 * between `persist()` and `merge()` by asking `isNew()`, and the default
 * answer is "the id is null". A pre-assigned id is never null, so every
 * insert would be treated as a possible update: Hibernate would issue a
 * `SELECT` to look for a row that cannot exist, then the `INSERT`. Two
 * statements for every user ever created, and nothing fails — you simply pay
 * twice, invisibly, forever. `UserPersistenceTest` asserts the statement
 * count rather than trusting this comment.
 *
 * **Not a `data class`.** `copy()` on a managed entity yields a detached
 * object with the same id that the persistence context knows nothing about;
 * saving it silently overwrites. `equals`/`hashCode` are by id, which is
 * safe here precisely because the id exists from construction — the usual
 * Kotlin/JPA contortion (a constant `hashCode`, a nullable `var id`) is a
 * workaround for database-generated ids that we do not have.
 */
@Entity
@Table(name = "users")
internal class UserEntity(
    // Private, with an explicit `getId()` below: Kotlin cannot satisfy a Java
    // interface's getter with a property (`'id' overrides nothing`), and a
    // *public* `val id` would generate a `getId()` of its own that clashes with
    // the one Persistable requires. A private property generates no accessor at
    // all, which leaves the name free for the override.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    // `citext` is not a type Hibernate knows, and the two halves of teaching
    // it are separate: `columnDefinition` is what `ddl-auto: validate`
    // compares against, and [CitextType] is what binds the *parameter*.
    // Without the second one the column still validates and still stores —
    // and every `WHERE email = ?` silently compares case-SENSITIVELY, because
    // a varchar parameter drags the comparison down to text. See CitextType.
    @Column(nullable = false, columnDefinition = "citext")
    @Type(CitextType::class)
    var email: String,
    var emailVerifiedAt: Instant?,
    @Column(nullable = false)
    var displayName: String,
    var avatarMediaId: UUID?,
    @Column(nullable = false)
    var locale: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: UserStatus,
    @Column(nullable = false, updatable = false)
    var createdAt: Instant,
    var updatedAt: Instant?,
    var deletedAt: Instant?,
    var tokensInvalidBefore: Instant?,
) : Persistable<UUID> {
    /**
     * Not a column — `@Transient` keeps it out of the schema. It starts true
     * for an object this code constructed and is flipped by the callbacks
     * below, which are the only two moments an entity becomes "a row that
     * exists": we just wrote it, or we just read it.
     */
    @Transient
    private var new: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = new

    @PostPersist
    @PostLoad
    private fun markNotNew() {
        new = false
    }

    override fun equals(other: Any?): Boolean = this === other || (other is UserEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "UserEntity(id=$id, status=$status)"
}
