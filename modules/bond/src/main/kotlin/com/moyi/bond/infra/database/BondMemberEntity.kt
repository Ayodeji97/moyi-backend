package com.moyi.bond.infra.database

import com.moyi.bond.domain.MemberRole
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
import java.time.LocalTime
import java.util.UUID

/**
 * The `bond_members` row.
 *
 * [bondId] is a plain column rather than a `@ManyToOne`, and that is the
 * aggregate boundary being honest: [BondStore] assembles a `Bond` with its
 * members in two queries it can see, instead of a lazy association that
 * decides for itself when to go to the database. Doc 25 §5 and the JPA skill
 * both name the Kotlin entity graph as the expensive mistake here, and the
 * aggregate is small enough that there is nothing to gain by taking it.
 *
 * `notification_prefs` is deliberately unmapped until Phase 4 — the column's
 * default fills it, and `validate` checks mapped columns only, so a column
 * nothing reads costs nothing.
 */
@Entity
@Table(name = "bond_members")
internal class BondMemberEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(nullable = false, updatable = false)
    var bondId: UUID,
    @Column(nullable = false, updatable = false)
    var userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: MemberRole,
    @Column(nullable = false, updatable = false)
    var joinedAt: Instant,
    var leftAt: Instant?,
    @Column(nullable = false)
    var reminderTimeLocal: LocalTime,
    @Column(nullable = false)
    var reminderTimezone: String,
    var quietHoursStart: LocalTime?,
    var quietHoursEnd: LocalTime?,
    var nicknameForOther: String?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is BondMemberEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    /** No nickname and no user id beyond the row's own: both are personal data. */
    override fun toString(): String = "BondMemberEntity(id=$id, bondId=$bondId)"
}
