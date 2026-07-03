package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import kotlin.time.Instant

@JvmInline
value class GoogleSubject private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.googleSubject(value: String): GoogleSubject {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankGoogleSubject }
            return GoogleSubject(trimmed)
        }
    }
}

@JvmInline
value class GoogleAccessToken private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.googleAccessToken(value: String): GoogleAccessToken {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankGoogleAccessToken }
            return GoogleAccessToken(trimmed)
        }
    }
}

@JvmInline
value class GoogleRefreshToken private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.googleRefreshToken(value: String): GoogleRefreshToken {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankGoogleRefreshToken }
            return GoogleRefreshToken(trimmed)
        }
    }
}

@JvmInline
value class GrantedGoogleScopes private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.grantedGoogleScopes(value: String): GrantedGoogleScopes {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankGrantedGoogleScopes }
            return GrantedGoogleScopes(trimmed)
        }
    }
}

class UserGoogleCredential(
    val userUuid: UserUuid,
    val googleSubject: GoogleSubject,
    accessToken: GoogleAccessToken,
    refreshToken: GoogleRefreshToken?,
    accessTokenExpiresAt: Instant,
    grantedScopes: GrantedGoogleScopes,
) {
    var accessToken: GoogleAccessToken = accessToken
        private set
    var refreshToken: GoogleRefreshToken? = refreshToken
        private set
    var accessTokenExpiresAt: Instant = accessTokenExpiresAt
        private set
    var grantedScopes: GrantedGoogleScopes = grantedScopes
        private set

    fun reauthorize(
        newAccessToken: GoogleAccessToken,
        newRefreshToken: GoogleRefreshToken?,
        newExpiresAt: Instant,
        scopes: GrantedGoogleScopes,
    ) {
        accessToken = newAccessToken
        refreshToken = newRefreshToken ?: refreshToken
        accessTokenExpiresAt = newExpiresAt
        grantedScopes = scopes
    }
}
