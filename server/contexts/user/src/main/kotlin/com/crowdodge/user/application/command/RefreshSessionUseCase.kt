package com.crowdodge.user.application.command

import arrow.core.Either
import arrow.core.left
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.AppRefreshToken
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import kotlin.time.Clock
import kotlin.time.Instant

data class RefreshSessionCommand(
    val refreshToken: String,
)

data class RefreshSessionResult(
    val accessToken: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

class RefreshSessionUseCase(
    private val userAuthRefreshTokenRepository: UserAuthRefreshTokenRepository,
    private val appTokenPort: AppTokenPort,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    suspend fun handle(command: RefreshSessionCommand): Either<UserError, RefreshSessionResult> {
        val tokenHash = appTokenPort.hashRefreshToken(command.refreshToken)
        val committed = transactionRunner.inTransaction {
            val now = clock.now()
            val currentToken = userAuthRefreshTokenRepository.consumeUsableByHash(tokenHash, now)
                ?: return@inTransaction UserError.AuthenticationError.InvalidRefreshToken.left()

            val newRefreshToken = appTokenPort.issueRefreshToken(currentToken.userUuid)
            userAuthRefreshTokenRepository.create(newRefreshToken.toDomain(currentToken.userUuid))

            Either.Right(currentToken.userUuid to newRefreshToken)
        }

        return committed.map { (userUuid, refreshToken) ->
            RefreshSessionResult(
                accessToken = appTokenPort.issueAccessToken(userUuid),
                refreshToken = refreshToken.plainText,
                refreshTokenExpiresAt = refreshToken.expiresAt,
            )
        }
    }

    private fun AppRefreshToken.toDomain(userUuid: UserUuid): UserAuthRefreshToken =
        UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = userUuid,
            tokenHash = hash,
            expiresAt = expiresAt,
            revokedAt = null,
        )
}
