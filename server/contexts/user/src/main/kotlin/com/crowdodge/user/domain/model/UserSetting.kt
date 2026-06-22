package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.Location
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import kotlin.time.Duration

/**
 * リマインドタイミング VO（user_settings.remind_timing、bigint ナノ秒として永続化）。
 * 予定の何分前に通知するかの既定値。正の値のみ許す。
 * 生成は [remindTiming] のみ。
 */
@JvmInline
value class RemindTiming private constructor(val duration: Duration) {
    companion object {
        fun Raise<UserError.ValidationError>.remindTiming(duration: Duration): RemindTiming {
            ensure(duration.isPositive()) { UserError.ValidationError.InvalidRemindTiming }
            return RemindTiming(duration)
        }
    }
}

/**
 * 自宅座標 [Location] を外部入力から生成する。範囲外は [UserError.ValidationError.InvalidHomeLocation] を `raise`。
 */
fun Raise<UserError.ValidationError>.home(longitude: Double, latitude: Double): Location {
    return Location.ofOrNull(longitude, latitude) ?: raise(UserError.ValidationError.InvalidHomeLocation)
}

@ConsistentCopyVisibility
data class UserSetting private constructor(
    val userUuid: UserUuid,
    val home: Location,
    val remindTiming: RemindTiming,
) {
    /** リマインドタイミング既定値のみを変更する。 */
    fun changeRemindTiming(remindTiming: RemindTiming): UserSetting =
        copy(remindTiming = remindTiming)

    companion object {
        /** 新規登録 */
        fun configure(userUuid: UserUuid, home: Location, remindTiming: RemindTiming): UserSetting =
            UserSetting(userUuid, home, remindTiming)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(userUuid: UserUuid, home: Location, remindTiming: RemindTiming): UserSetting =
            UserSetting(userUuid, home, remindTiming)
    }
}
