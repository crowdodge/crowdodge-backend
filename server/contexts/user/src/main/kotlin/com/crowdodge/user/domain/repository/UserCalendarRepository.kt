package com.crowdodge.user.domain.repository

import arrow.core.Either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserCalendarUuid

/**
 * UserCalendar 集約の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface UserCalendarRepository {
    /** カレンダー選択を新規登録する。同一ユーザー×同一カレンダーの重複は [UserError.ConflictError.DuplicateCalendar]。 */
    suspend fun create(userCalendar: UserCalendar): Either<UserError.ConflictError.DuplicateCalendar, Unit>

    /** 所有者スコープ付き削除（自分のカレンダーのみ）。 */
    suspend fun delete(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid)

    /** 当該ユーザーが選択中のカレンダー一覧。 */
    suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar>
}
