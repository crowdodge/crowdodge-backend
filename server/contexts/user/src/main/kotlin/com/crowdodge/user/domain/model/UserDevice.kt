package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.EntityUuid
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import kotlin.uuid.Uuid

/** 通知デバイス識別子（user_devices.device_uuid）。 */
@JvmInline
value class UserDeviceUuid(override val value: Uuid) : EntityUuid {
    companion object {
        fun new(): UserDeviceUuid = UserDeviceUuid(Uuid.random())
    }
}

/**
 * FCM デバイストークン VO（user_devices.fcm_token）。空文字を許さない。
 * 生成は [FcmToken.fcmToken] のみ（private constructor で施錠）。
 */
@JvmInline
value class FcmToken private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.fcmToken(value: String): FcmToken {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankFcmToken }
            return FcmToken(trimmed)
        }
    }
}

/**
 * 通知対象デバイス（user_devices）。独立集約。所有者は [userUuid] を値参照する。
 */
class UserDevice private constructor(
    val userDeviceUuid: UserDeviceUuid,
    val userUuid: UserUuid,
    val fcmToken: FcmToken,
) {
    companion object {
        /** 新規登録（新しい [UserDeviceUuid] を採番）。 */
        fun register(userUuid: UserUuid, fcmToken: FcmToken): UserDevice =
            UserDevice(UserDeviceUuid.new(), userUuid, fcmToken)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(
            userDeviceUuid: UserDeviceUuid,
            userUuid: UserUuid,
            fcmToken: FcmToken,
        ): UserDevice = UserDevice(userDeviceUuid, userUuid, fcmToken)
    }
}
