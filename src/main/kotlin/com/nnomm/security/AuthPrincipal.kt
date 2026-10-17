package com.nnomm.security

import com.nnomm.domain.UserRole
import java.util.UUID

data class AuthPrincipal(
    val sub: UUID,
    val role: UserRole,
    val restaurantId: UUID?
)
