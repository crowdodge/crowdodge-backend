package com.crowdodge.user.domain.repository

import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.domain.model.UserDeviceId

/**
 * UserDevice 集約の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface UserDeviceRepository {
    suspend fun save(userDevice: UserDevice)

    suspend fun delete(id: UserDeviceId)

    /** 当該ユーザーの通知対象デバイス一覧（FCM 配信先）。 */
    suspend fun findByUserId(userId: UserId): List<UserDevice>
}
