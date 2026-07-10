package com.crowdodge.user.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.domain.repository.UserDeviceRepository

data class RegisterUserDeviceCommand(
    val userUuid: UserUuid,
    val fcmToken: String,
)

/**
 * デバイス登録（POST /v1/devices）。
 * 同一トークンが別ユーザーに紐づいていた場合は、既存 deviceUuid を維持して現ユーザーへ付け替える。
 * 同一トークンが既に同一ユーザーに紐づいている場合は何もしない。
 */
class RegisterUserDeviceUseCase(
    private val devices: UserDeviceRepository,
    private val transactions: TransactionRunner,
) {
    suspend fun handle(command: RegisterUserDeviceCommand): Either<UserError.ValidationError, Unit> =
        either {
            val token = fcmToken(command.fcmToken)
            transactions.inTransaction {
                val existing = devices.findByFcmToken(token)

                when {
                    existing == null ->
                        devices.save(UserDevice.register(command.userUuid, token))

                    existing.userUuid != command.userUuid ->
                        devices.save(existing.transferTo(command.userUuid))

                    else ->
                        Unit
                }
            }
        }
}
