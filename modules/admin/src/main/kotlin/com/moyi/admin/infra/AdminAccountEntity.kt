package com.moyi.admin.infra

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AdminStatus
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
 * The `admin_accounts` row (spec §5). Admins are not users and never appear in
 * `users`. Implements [Persistable] and is not a `data class`, for the reasons
 * identity's `UserEntity` gives: the id is assigned in code, so `save()` must
 * not be left to guess between `persist` and `merge`.
 */
@Entity
@Table(name = "admin_accounts")
internal class AdminAccountEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    // `citext`; the lookup is a native query with an explicit cast, see
    // AdminAccountRepository.findByEmail.
    @Column(nullable = false, columnDefinition = "citext")
    var email: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: AdminRole,
    var passwordHash: String?,
    @Column(nullable = false)
    var credentialsChangedAt: Instant,
    var totpSecretEnc: String?,
    var totpLastStep: Long?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AdminStatus,
    @Column(nullable = false)
    var failedAttempts: Int,
    var lockedUntil: Instant?,
    @Column(nullable = false, updatable = false)
    var createdAt: Instant,
    var activatedAt: Instant?,
    var disabledAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is AdminAccountEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    // Never the email, the hash or the secret: this reaches logs.
    override fun toString(): String = "AdminAccountEntity(id=$id, status=$status)"
}
