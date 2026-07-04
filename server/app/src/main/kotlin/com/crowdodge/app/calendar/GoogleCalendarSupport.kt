package com.crowdodge.app.calendar

import com.crowdodge.event.infrastructure.google.GoogleCalendarConfig
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.infrastructure.google.GoogleOAuthConfig
import io.ktor.server.application.ApplicationEnvironment
import kotlin.time.Duration.Companion.seconds

fun ApplicationEnvironment.googleCalendarConfig(): GoogleCalendarConfig {
    val c = config.config("crowdodge.googleCalendar")
    return GoogleCalendarConfig(
        apiBaseUrl = c.property("apiBaseUrl").getString().trimEnd('/'),
        webhookUrl = c.property("webhookUrl").getString().also {
            require(it.isNotBlank()) { "Google Calendar webhook URL must not be blank" }
        },
        channelToken = c.property("channelToken").getString().also {
            require(it.isNotBlank()) { "Google Calendar channel token must not be blank" }
        },
        fullSyncWindowDays = c.property("fullSyncWindowDays").getString().toInt(),
    )
}

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
