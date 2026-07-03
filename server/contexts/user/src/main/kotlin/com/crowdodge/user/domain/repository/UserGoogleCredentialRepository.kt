package com.crowdodge.user.domain.repository

import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.UserGoogleCredential
import kotlin.time.Instant

interface UserGoogleCredentialRepository {
    suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential?

    suspend fun upsert(credential: UserGoogleCredential)

    suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    )
}
