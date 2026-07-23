package com.crowdodge.user.domain.repository

import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.FcmToken
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.domain.model.UserDeviceUuid

/**
 * UserDevice 集約の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface UserDeviceRepository {
    suspend fun save(userDevice: UserDevice)

    /** 所有者スコープ付き削除（自分のデバイスのみ）。 */
    suspend fun delete(userUuid: UserUuid, userDeviceUuid: UserDeviceUuid)

    /** 当該ユーザーの通知対象デバイス一覧（FCM 配信先）。 */
    suspend fun findByUserUuid(userUuid: UserUuid): List<UserDevice>

    /** fcm_token での逆引き。 */
    suspend fun findByFcmToken(fcmToken: FcmToken): UserDevice?
}
