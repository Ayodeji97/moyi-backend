package com.moyi.identity.infra.database

import com.moyi.identity.domain.Device
import com.moyi.identity.domain.DevicePlatform
import com.moyi.identity.domain.UserId
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

/** The `devices` row (V8). Same [Persistable] arrangement as the others. */
@Entity
@Table(name = "devices")
internal class DeviceEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val id: UUID,
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, updatable = false)
    var platform: DevicePlatform,
    @Column(name = "push_token", length = 512)
    var pushToken: String?,
    @Column(name = "app_version", nullable = false, length = 40)
    var appVersion: String,
    @Column(name = "os_version", nullable = false, length = 40)
    var osVersion: String,
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant?,
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

    override fun equals(other: Any?): Boolean = this === other || (other is DeviceEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "DeviceEntity(id=$id, platform=$platform, appVersion=$appVersion)"
}

internal fun DeviceEntity.toDomain(): Device =
    Device(
        id = getId(),
        userId = UserId(userId),
        platform = platform,
        appVersion = appVersion,
        osVersion = osVersion,
        lastSeenAt = lastSeenAt,
        pushToken = pushToken,
        revokedAt = revokedAt,
    )

internal fun Device.toEntity(): DeviceEntity =
    DeviceEntity(
        id = id,
        userId = userId.value,
        platform = platform,
        pushToken = pushToken,
        appVersion = appVersion,
        osVersion = osVersion,
        lastSeenAt = lastSeenAt,
        revokedAt = revokedAt,
    )
