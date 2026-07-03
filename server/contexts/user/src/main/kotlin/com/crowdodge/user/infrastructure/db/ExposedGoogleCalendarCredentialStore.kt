package com.crowdodge.user.infrastructure.db

import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarCredential
import com.crowdodge.user.application.port.GoogleCalendarCredentialStore
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UserGoogleCredentialsTable
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.Instant

class ExposedGoogleCalendarCredentialStore(
    tokenCipher: TokenCipher,
) : GoogleCalendarCredentialStore {
    private val tokenCodec = GoogleCalendarCredentialTokenCodec(tokenCipher)
    private val resolver = GoogleCalendarCredentialResolver(tokenCodec)

    @Suppress("ReturnCount")
    override suspend fun find(userCalendarUuid: UserCalendarUuid): GoogleCalendarCredential? {
        val calendar = UserCalendarsTable.selectAll()
            .where { UserCalendarsTable.userCalendarUuid eq userCalendarUuid.value }
            .firstOrNull()
            ?.let {
                GoogleCalendarCredentialCalendarRow(
                    userCalendarUuid = userCalendarUuid,
                    userUuid = UserUuid(it[UserCalendarsTable.userUuid]),
                    googleCalendarId = it[UserCalendarsTable.googleCalendarId],
                )
            }
            ?: return null
        val credential = UserGoogleCredentialsTable.selectAll()
            .where { UserGoogleCredentialsTable.userUuid eq calendar.userUuid.value }
            .firstOrNull()
            ?.let {
                GoogleCalendarCredentialTokenRow(
                    userUuid = UserUuid(it[UserGoogleCredentialsTable.userUuid]),
                    accessToken = it[UserGoogleCredentialsTable.accessToken],
                    refreshToken = it[UserGoogleCredentialsTable.refreshToken],
                    expiresAt = it[UserGoogleCredentialsTable.accessTokenExpiresAt],
                )
            }
            ?: return null

        return resolver.resolve(calendar, credential)
    }

    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: String,
        expiresAt: Instant,
    ) {
        UserGoogleCredentialsTable.update({ UserGoogleCredentialsTable.userUuid eq userUuid.value }) {
            it[UserGoogleCredentialsTable.accessToken] = tokenCodec.encryptAccessToken(accessToken)
            it[UserGoogleCredentialsTable.accessTokenExpiresAt] = expiresAt
            it[updatedAt] = Clock.System.now()
        }
    }
}

internal class GoogleCalendarCredentialTokenCodec(
    private val tokenCipher: TokenCipher,
) {
    fun decryptAccessToken(value: String): String = tokenCipher.decrypt(value)

    fun decryptRefreshToken(value: String?): String? = value?.let(tokenCipher::decrypt)

    fun encryptAccessToken(value: String): String = tokenCipher.encrypt(value)
}

internal data class GoogleCalendarCredentialCalendarRow(
    val userCalendarUuid: UserCalendarUuid,
    val userUuid: UserUuid,
    val googleCalendarId: String,
)

internal data class GoogleCalendarCredentialTokenRow(
    val userUuid: UserUuid,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
)

internal class GoogleCalendarCredentialResolver(
    private val tokenCodec: GoogleCalendarCredentialTokenCodec,
) {
    fun resolve(
        calendar: GoogleCalendarCredentialCalendarRow,
        credential: GoogleCalendarCredentialTokenRow,
    ): GoogleCalendarCredential? {
        if (calendar.userUuid != credential.userUuid) return null
        return GoogleCalendarCredential(
            userUuid = calendar.userUuid,
            googleCalendarId = calendar.googleCalendarId,
            accessToken = tokenCodec.decryptAccessToken(credential.accessToken),
            refreshToken = tokenCodec.decryptRefreshToken(credential.refreshToken),
            expiresAt = credential.expiresAt,
        )
    }
}
