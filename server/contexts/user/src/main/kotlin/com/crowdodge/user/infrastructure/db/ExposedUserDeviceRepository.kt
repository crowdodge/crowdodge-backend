package com.crowdodge.user.infrastructure.db

import arrow.core.getOrElse
import arrow.core.raise.either
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.FcmToken
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.domain.model.UserDeviceUuid
import com.crowdodge.user.domain.repository.UserDeviceRepository
import com.crowdodge.user.infrastructure.persistence.UserDevicesTable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

/**
 * [UserDeviceRepository] の Exposed(R2DBC) 実装。
 * トランザクションは UseCase が TransactionRunner で開き、本実装はその tx に参加する（§11）。
 */
class ExposedUserDeviceRepository : UserDeviceRepository {

    override suspend fun save(userDevice: UserDevice) {
        // fcm_token は全体で一意（1トークン=1デバイス=1ユーザー）。同一トークンの再登録は冪等に更新する。
        // device_uuid（PK）は競合時に上書きせず既存行のものを保つ。
        UserDevicesTable.upsert(UserDevicesTable.fcmToken, onUpdateExclude = listOf(UserDevicesTable.deviceUuid)) {
            it[userUuid] = userDevice.userUuid.value
            it[deviceUuid] = userDevice.userDeviceUuid.value
            it[fcmToken] = userDevice.fcmToken.value
        }
    }

    override suspend fun delete(userUuid: UserUuid, userDeviceUuid: UserDeviceUuid) {
        // 所有者スコープ付き削除（他ユーザーのデバイスは消せない）。
        UserDevicesTable.deleteWhere {
            (UserDevicesTable.deviceUuid eq userDeviceUuid.value) and
                (UserDevicesTable.userUuid eq userUuid.value)
        }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserDevice> =
        UserDevicesTable.selectAll()
            .where { UserDevicesTable.userUuid eq userUuid.value }
            .map { toDomain(it) }
            .toList()

    override suspend fun findByFcmToken(fcmToken: FcmToken): UserDevice? =
        UserDevicesTable.selectAll()
            .where { UserDevicesTable.fcmToken eq fcmToken.value }
            .firstOrNull()
            ?.let { toDomain(it) }

    private fun toDomain(row: ResultRow): UserDevice =
        either {
            UserDevice.reconstitute(
                userUuid = UserUuid(row[UserDevicesTable.userUuid]),
                userDeviceUuid = UserDeviceUuid(row[UserDevicesTable.deviceUuid]),
                fcmToken = fcmToken(row[UserDevicesTable.fcmToken]),
            )
        }.getOrElse { throw PersistedDataCorruption("User Device の復元に失敗しました: ${it.name}") }
}
