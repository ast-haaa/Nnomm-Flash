package com.nnomm.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.config.AppConfig
import com.nnomm.domain.AppException
import com.nnomm.domain.UnauthenticatedException
import com.nnomm.domain.UserRole
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID

@Component
class JwtService(private val secret: String) {

    @Autowired
    constructor(config: AppConfig) : this(config.jwtSecret)

    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    fun verifyToken(token: String): AuthPrincipal {
        try {
            val decoded = verifier.verify(token)
            if (decoded.algorithm != "HS256") {
                throw UnauthenticatedException("Algorithm must be HS256")
            }

            val subStr = decoded.getClaim("sub").asString() ?: throw UnauthenticatedException("Missing sub claim")
            val sub = try {
                UUID.fromString(subStr)
            } catch (e: Exception) {
                throw UnauthenticatedException("Invalid sub UUID")
            }

            val roleStr = decoded.getClaim("role").asString() ?: throw UnauthenticatedException("Missing role claim")
            val role = try {
                UserRole.valueOf(roleStr)
            } catch (e: Exception) {
                throw UnauthenticatedException("Invalid role claim")
            }

            val restaurantId = if (role == UserRole.restaurant_admin) {
                val resStr = decoded.getClaim("restaurant_id").asString()
                    ?: throw UnauthenticatedException("Missing restaurant_id for restaurant_admin")
                try {
                    UUID.fromString(resStr)
                } catch (e: Exception) {
                    throw UnauthenticatedException("Invalid restaurant_id UUID")
                }
            } else {
                null
            }

            return AuthPrincipal(
                sub = sub,
                role = role,
                restaurantId = restaurantId
            )
        } catch (e: AppException) {
            throw e
        } catch (e: Exception) {
            throw UnauthenticatedException("Invalid or expired token: ${e.message}")
        }
    }

    fun mintToken(
        sub: UUID,
        role: UserRole,
        restaurantId: UUID? = null,
        expiresAt: Date = Date(System.currentTimeMillis() + 86400000)
    ): String {
        val builder = JWT.create()
            .withClaim("sub", sub.toString())
            .withClaim("role", role.name)
            .withExpiresAt(expiresAt)

        if (restaurantId != null) {
            builder.withClaim("restaurant_id", restaurantId.toString())
        }

        return builder.sign(algorithm)
    }
}
