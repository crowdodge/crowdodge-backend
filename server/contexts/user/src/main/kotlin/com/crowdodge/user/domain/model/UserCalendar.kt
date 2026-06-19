package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.EntityId
import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.error.UserError
import kotlin.uuid.Uuid

/** ユーザーカレンダー識別子（user_calendars.user_calendar_uuid）。 */
@JvmInline
value class UserCalendarId(override val value: Uuid) : EntityId {
    companion object {
        fun new(): UserCalendarId = UserCalendarId(Uuid.random())
    }
}

/**
 * Google カレンダー識別子 VO（user_calendars.google_calendar_id）。空文字を許さない。
 * 生成は [GoogleCalendarId.googleCalendarId] のみ（private constructor で施錠）。
 */
@JvmInline
value class GoogleCalendarId private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.googleCalendarId(value: String): GoogleCalendarId {
            ensure(value.isNotBlank()) { UserError.ValidationError.BlankGoogleCalendarId }
            return GoogleCalendarId(value)
        }
    }
}

/**
 * ユーザーが混雑回避の対象に選択した Google カレンダー（user_calendars）。独立集約。
 * 所有者は [userId] を値参照する。
 *
 * 「同一ユーザー×同一カレンダーの重複禁止」は集約跨ぎの制約のため、
 * DB の `UNIQUE(user_uuid, google_calendar_id)` と application 層で担保する（結果整合）。
 * ドメインは単体の不変条件（VO の検証）のみを保証する。
 */
class UserCalendar private constructor(
    val id: UserCalendarId,
    val userId: UserId,
    val googleCalendarId: GoogleCalendarId,
) {

    companion object {
        /** 新規選択（新しい [UserCalendarId] を採番）。 */
        fun select(userId: UserId, googleCalendarId: GoogleCalendarId): UserCalendar =
            UserCalendar(UserCalendarId.new(), userId, googleCalendarId)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(
            id: UserCalendarId,
            userId: UserId,
            googleCalendarId: GoogleCalendarId,
        ): UserCalendar = UserCalendar(id, userId, googleCalendarId)
    }
}
