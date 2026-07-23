package com.crowdodge.user.infrastructure.db

import arrow.core.getOrElse
import arrow.core.raise.either
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleRefreshToken.Companion.googleRefreshToken
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.infrastructure.persistence.UserGoogleCredentialsTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.Clock
import kotlin.time.Instant

class ExposedUserGoogleCredentialRepository(
    private val tokenCipher: TokenCipher,
) : UserGoogleCredentialRepository {
    override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential? =
        UserGoogleCredentialsTable.selectAll()
            .where { UserGoogleCredentialsTable.userUuid eq userUuid.value }
            .firstOrNull()
            ?.let(::toDomain)

    override suspend fun upsert(credential: UserGoogleCredential) {
        val normalizedScopes = normalizeGrantedScopes(credential.grantedScopes.value)
        val encryptedAccessToken = tokenCipher.encrypt(credential.accessToken.value)
        val encryptedRefreshToken = credential.refreshToken?.value?.let(tokenCipher::encrypt)
        UserGoogleCredentialsTable.upsert(
            onUpdateExclude = listOf(UserGoogleCredentialsTable.createdAt),
        ) {
            it[UserGoogleCredentialsTable.userUuid] = credential.userUuid.value
            it[UserGoogleCredentialsTable.googleSubject] = credential.googleSubject.value
            it[UserGoogleCredentialsTable.accessToken] = encryptedAccessToken
            it[UserGoogleCredentialsTable.refreshToken] = encryptedRefreshToken
            it[UserGoogleCredentialsTable.accessTokenExpiresAt] = credential.accessTokenExpiresAt
            it[UserGoogleCredentialsTable.grantedScopes] = normalizedScopes
        }
    }

    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    ) {
        UserGoogleCredentialsTable.update({ UserGoogleCredentialsTable.userUuid eq userUuid.value }) {
            it[UserGoogleCredentialsTable.accessToken] = tokenCipher.encrypt(accessToken.value)
            it[UserGoogleCredentialsTable.accessTokenExpiresAt] = accessTokenExpiresAt
            it[updatedAt] = Clock.System.now()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun toDomain(row: ResultRow): UserGoogleCredential =
        try {
            val accessToken = tokenCipher.decrypt(row[UserGoogleCredentialsTable.accessToken])
            val refreshToken = row[UserGoogleCredentialsTable.refreshToken]?.let(tokenCipher::decrypt)

            either {
                UserGoogleCredential(
                    userUuid = UserUuid(row[UserGoogleCredentialsTable.userUuid]),
                    googleSubject = googleSubject(row[UserGoogleCredentialsTable.googleSubject]),
                    accessToken = googleAccessToken(accessToken),
                    refreshToken = refreshToken?.let { googleRefreshToken(it) },
                    accessTokenExpiresAt = row[UserGoogleCredentialsTable.accessTokenExpiresAt],
                    grantedScopes = grantedGoogleScopes(
                        normalizeGrantedScopes(row[UserGoogleCredentialsTable.grantedScopes])
                    ),
                )
            }.getOrElse {
                throw PersistedDataCorruption(
                    "UserGoogleCredential の復元に失敗しました: ${it.code}",
                    IllegalArgumentException(it.code),
                )
            }
        } catch (e: PersistedDataCorruption) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PersistedDataCorruption(
                "UserGoogleCredential の復元に失敗しました: ${e.message ?: e::class.simpleName}",
                e,
            )
        }

    private fun normalizeGrantedScopes(value: String): String =
        value.split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }
            .joinToString(SCOPE_SEPARATOR)

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
        const val SCOPE_SEPARATOR = " "
    }
}
