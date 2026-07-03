package com.crowdodge.user.infrastructure.db

import arrow.core.getOrElse
import arrow.core.raise.either
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import com.crowdodge.user.domain.model.AuthRefreshTokenHash.Companion.authRefreshTokenHash
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import com.crowdodge.user.infrastructure.persistence.UserAuthRefreshTokensTable
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Instant

class ExposedUserAuthRefreshTokenRepository : UserAuthRefreshTokenRepository {
    override suspend fun create(refreshToken: UserAuthRefreshToken) {
        UserAuthRefreshTokensTable.insert {
            it[refreshTokenUuid] = refreshToken.refreshTokenUuid.value
            it[userUuid] = refreshToken.userUuid.value
            it[tokenHash] = refreshToken.tokenHash.value
            it[expiresAt] = refreshToken.expiresAt
            it[revokedAt] = refreshToken.revokedAt
        }
    }

    override suspend fun findByHash(tokenHash: AuthRefreshTokenHash): UserAuthRefreshToken? =
        UserAuthRefreshTokensTable.selectAll()
            .where { UserAuthRefreshTokensTable.tokenHash eq tokenHash.value }
            .firstOrNull()
            ?.let(::toDomain)

    override suspend fun consumeUsableByHash(
        tokenHash: AuthRefreshTokenHash,
        now: Instant,
    ): UserAuthRefreshToken? {
        val updated = UserAuthRefreshTokensTable.update({
            (UserAuthRefreshTokensTable.tokenHash eq tokenHash.value) and
                UserAuthRefreshTokensTable.revokedAt.isNull() and
                (UserAuthRefreshTokensTable.expiresAt greater now)
        }) {
            it[revokedAt] = now
            it[updatedAt] = now
        }

        return if (updated == 1) {
            findByHash(tokenHash)
        } else {
            null
        }
    }

    override suspend fun revoke(refreshTokenUuid: UserAuthRefreshTokenUuid, revokedAt: Instant) {
        UserAuthRefreshTokensTable.update({
            (UserAuthRefreshTokensTable.refreshTokenUuid eq refreshTokenUuid.value) and
                UserAuthRefreshTokensTable.revokedAt.isNull()
        }) {
            it[UserAuthRefreshTokensTable.revokedAt] = revokedAt
            it[updatedAt] = revokedAt
        }
    }

    private fun toDomain(row: ResultRow): UserAuthRefreshToken =
        either {
            UserAuthRefreshToken(
                refreshTokenUuid = UserAuthRefreshTokenUuid(row[UserAuthRefreshTokensTable.refreshTokenUuid]),
                userUuid = UserUuid(row[UserAuthRefreshTokensTable.userUuid]),
                tokenHash = authRefreshTokenHash(row[UserAuthRefreshTokensTable.tokenHash]),
                expiresAt = row[UserAuthRefreshTokensTable.expiresAt],
                revokedAt = row[UserAuthRefreshTokensTable.revokedAt],
            )
        }.getOrElse { throw PersistedDataCorruption("UserAuthRefreshToken の復元に失敗しました: ${it.code}") }
}
