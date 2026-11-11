package com.moyi.admin.infra

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * One `audit_log` row (spec §8). Insert-only: the database refuses an update or
 * a delete, so nothing here is `var`. Rows the app writes are always
 * `actor_type = 'ADMIN'`; the operator functions write the `OPERATOR` ones.
 */
@Entity
@Table(name = "audit_log")
internal class AuditLogEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    val occurredAt: Instant,
    @Column(nullable = false, updatable = false)
    val actorType: String,
    @Column(updatable = false)
    val actorId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val action: AuditAction,
    @Column(updatable = false)
    val targetType: String?,
    @Column(updatable = false)
    val targetId: String?,
    @Column(updatable = false)
    val justification: String?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val outcome: AuditOutcome,
    // JSON text, written by AuditTrail with the application's mapper.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    val metadata: String,
    @Column(updatable = false)
    val ipHash: ByteArray?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is AuditLogEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AuditLogEntity(id=$id, action=$action, outcome=$outcome)"
}
