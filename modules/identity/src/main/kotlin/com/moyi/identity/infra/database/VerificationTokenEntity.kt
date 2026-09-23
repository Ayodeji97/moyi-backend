package com.moyi.identity.infra.database

import com.moyi.identity.domain.VerificationPurpose
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
 * The `verification_tokens` row. Same [Persistable] arrangement as
 * [UserEntity], for the same app-assigned-id reason.
 *
 * `user_id` is a plain column, as on [ConsentRecordEntity]: the verification
 * path looks the user up by id once, in the service, and an association here
 * would add a lazy proxy and a fetch decision to a row that is read once and
 * spent.
 *
 * `tokenHash` is `updatable = false` — a token is never re-keyed, only
 * consumed — and `consumedAt` is the one column that changes, once. The bulk
 * `UPDATE` that flips it lives on the repository rather than here because it
 * has to be conditional to be safe; see [VerificationTokenRepository.consume].
 */
@Entity
@Table(name = "verification_tokens")
internal class VerificationTokenEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    var purpose: VerificationPurpose,
    @Column(nullable = false, updatable = false)
    var tokenHash: String,
    @Column(nullable = false, updatable = false)
    var expiresAt: Instant,
    var consumedAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is VerificationTokenEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    /** No hash in here either: a digest is not the secret, but it is the lookup key, and a log is not where keys go. */
    override fun toString(): String =
        "VerificationTokenEntity(id=$id, purpose=$purpose, expiresAt=$expiresAt, consumed=${consumedAt != null})"
}
