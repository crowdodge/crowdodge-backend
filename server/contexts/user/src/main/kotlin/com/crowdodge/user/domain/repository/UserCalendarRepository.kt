package com.crowdodge.user.domain.repository

import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserCalendarId

/**
 * UserCalendar 集約の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface UserCalendarRepository {
    suspend fun save(userCalendar: UserCalendar)

    suspend fun delete(id: UserCalendarId)

    /** 当該ユーザーが選択中のカレンダー一覧。 */
    suspend fun findByUserId(userId: UserId): List<UserCalendar>
}
