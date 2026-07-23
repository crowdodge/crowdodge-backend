package com.crowdodge.user.application.command

import arrow.core.Either
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import kotlin.time.Clock

data class LogoutCommand(
    val refreshToken: String,
)

class LogoutUseCase(
    private val userAuthRefreshTokenRepository: UserAuthRefreshTokenRepository,
    private val appTokenPort: AppTokenPort,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    suspend fun handle(command: LogoutCommand): Either<UserError, Unit> {
        val tokenHash = appTokenPort.hashRefreshToken(command.refreshToken)
        transactionRunner.inTransaction {
            userAuthRefreshTokenRepository.consumeUsableByHash(tokenHash, clock.now())
        }
        return Unit.right()
    }
}
