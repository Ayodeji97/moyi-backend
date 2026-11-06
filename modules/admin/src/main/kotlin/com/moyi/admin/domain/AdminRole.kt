package com.moyi.admin.domain

/** An admin's role; `ADMIN > MODERATOR > SUPPORT` through the role hierarchy (spec §5). */
internal enum class AdminRole { SUPPORT, MODERATOR, ADMIN }
