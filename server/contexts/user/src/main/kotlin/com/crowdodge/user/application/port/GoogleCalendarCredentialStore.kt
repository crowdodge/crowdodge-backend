package com.crowdodge.user.application.port

import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.UserCalendarUuid
import kotlin.time.Instant

data class GoogleCalendarCredential(
    val userUuid: UserUuid,
    val googleCalendarId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
)

interface GoogleCalendarCredentialStore {
    suspend fun find(userCalendarUuid: UserCalendarUuid): GoogleCalendarCredential?

    suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: String,
        expiresAt: Instant,
    )
}
