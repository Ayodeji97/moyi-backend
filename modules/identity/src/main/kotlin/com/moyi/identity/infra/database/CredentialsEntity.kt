package com.moyi.identity.infra.database

import com.moyi.identity.domain.PasswordHashAlgorithm
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * The `credentials` row: one per user, keyed by the user's own id.
 *
 * Same [Persistable] reasoning as [UserEntity], and for a sharper reason —
 * the id here is a *foreign* key, so "is there already a row with this id"
 * is a question the default `isNew()` cannot answer from the id's nullity at
 * all.
 *
 * [toString] names no field. This object holds a password hash, and doc 18 §9
 * puts secrets out of logs unconditionally; the hash itself is additionally
 * wrapped in `PasswordHash`, which refuses to print. Two layers, because the
 * cost is a line and the failure is permanent.
 */
@Entity
@Table(name = "credentials")
internal class CredentialsEntity(
    // The primary key *is* the foreign key, so the column keeps the name the
    // schema gives it; `id` is the name Persistable requires.
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false)
    var passwordHash: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var algorithm: PasswordHashAlgorithm,
    @Column(nullable = false)
    var passwordUpdatedAt: Instant,
    @Column(nullable = false)
    var failedAttempts: Int,
    var lockedUntil: Instant?,
) : Persistable<UUID> {
    @Transient
    private var new: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = new

    @PostPersist
    @PostLoad
    private fun markNotNew() {
        new = false
    }

    override fun equals(other: Any?): Boolean = this === other || (other is CredentialsEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "CredentialsEntity(userId=$id)"
}
