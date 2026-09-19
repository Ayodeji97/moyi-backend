package com.moyi.identity.infra.database

import com.moyi.identity.domain.ConsentDocument
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
 * The `consent_records` row. Same [Persistable] arrangement as [UserEntity] —
 * see there for why an application-assigned id needs it.
 *
 * `user_id` is a plain column rather than a `@ManyToOne` to [UserEntity].
 * Nothing in this slice navigates from a consent record to its user, and an
 * association would buy a lazy proxy, a fetch decision and an
 * N+1 opportunity in exchange for nothing. It becomes an association when
 * something actually traverses it.
 */
@Entity
@Table(name = "consent_records")
internal class ConsentRecordEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    var document: ConsentDocument,
    @Column(nullable = false, updatable = false)
    var version: String,
    @Column(nullable = false, updatable = false)
    var acceptedAt: Instant,
    var ipHash: String?,
    var userAgentHash: String?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is ConsentRecordEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ConsentRecordEntity(id=$id, document=$document, version=$version)"
}
