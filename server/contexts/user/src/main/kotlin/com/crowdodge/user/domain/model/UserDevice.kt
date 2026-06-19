package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.EntityId
import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.error.UserError
import kotlin.uuid.Uuid

/** 通知デバイス識別子（user_devices.device_uuid）。 */
@JvmInline
value class UserDeviceId(override val value: Uuid) : EntityId {
    companion object {
        fun new(): UserDeviceId = UserDeviceId(Uuid.random())
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
 * 通知対象デバイス（user_devices）。独立集約。所有者は [userId] を値参照する。
 */
class UserDevice private constructor(
    val id: UserDeviceId,
    val userId: UserId,
    val fcmToken: FcmToken,
) {
    companion object {
        /** 新規登録（新しい [UserDeviceId] を採番）。 */
        fun register(userId: UserId, fcmToken: FcmToken): UserDevice =
            UserDevice(UserDeviceId.new(), userId, fcmToken)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(
            id: UserDeviceId,
            userId: UserId,
            fcmToken: FcmToken,
        ): UserDevice = UserDevice(id, userId, fcmToken)
    }
}
