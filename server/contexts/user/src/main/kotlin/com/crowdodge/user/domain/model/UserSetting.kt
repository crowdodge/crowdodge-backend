package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.Location
import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.error.UserError
import kotlin.time.Duration

/**
 * リマインドタイミング VO（user_settings.remind_timing の `interval`）。
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
 * Homeの生成関数。
 * KernelのLocationを扱う。
 * 生成は [Location]
 */
fun Raise<UserError.ValidationError>.home(longitude: Double, latitude: Double): Location {
    return Location.ofOrNull(longitude, latitude) ?: raise(UserError.ValidationError.InvalidHomeLocation)
}

@ConsistentCopyVisibility
data class UserSetting private constructor(
    val userId: UserId,
    val home: Location,
    val remindTiming: RemindTiming,
) {
    /** リマインドタイミング既定値のみを変更する。 */
    fun changeRemindTiming(remindTiming: RemindTiming): UserSetting =
        copy(remindTiming = remindTiming)

    companion object {
        /** 新規登録 */
        fun configure(userId: UserId, home: Location, remindTiming: RemindTiming): UserSetting =
            UserSetting(userId, home, remindTiming)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(userId: UserId, home: Location, remindTiming: RemindTiming): UserSetting =
            UserSetting(userId, home, remindTiming)
    }
}
