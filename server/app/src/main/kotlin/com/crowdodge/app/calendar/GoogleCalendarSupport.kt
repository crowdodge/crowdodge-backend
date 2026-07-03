package com.crowdodge.app.calendar

import com.crowdodge.user.infrastructure.google.GoogleOAuthConfig
import com.crowdodge.user.infrastructure.security.JwtAppTokenConfig
import io.ktor.server.application.ApplicationEnvironment
import kotlin.time.Duration.Companion.seconds

fun ApplicationEnvironment.googleOAuthConfig(): GoogleOAuthConfig {
    val c = config.config("crowdodge.googleCalendar")
    return GoogleOAuthConfig(
        tokenUrl = c.property("oauthTokenUrl").getString(),
        clientId = c.property("oauthClientId").getString().also {
            require(it.isNotBlank()) { "Google OAuth client ID must not be blank" }
        },
        clientSecret = c.property("oauthClientSecret").getString(),
        jwksUrl = c.property("oauthJwksUrl").getString(),
    )
}

fun ApplicationEnvironment.jwtAppTokenConfig(): JwtAppTokenConfig {
    val c = config.config("crowdodge.auth.jwt")
    return JwtAppTokenConfig(
        issuer = c.property("issuer").getString(),
        audience = c.property("audience").getString(),
        secret = c.property("secret").getString(),
        accessTokenTtl = c.property("accessTokenTtlSeconds").getString().toLong().seconds,
        refreshTokenTtl = c.property("refreshTokenTtlSeconds").getString().toLong().seconds,
    )
}

fun ApplicationEnvironment.googleTokenEncryptionKey(): String =
    config.config("crowdodge.googleCalendar").property("tokenEncryptionKey").getString().also {
        require(it.isNotBlank()) { "Google token encryption key must not be blank" }
    }
