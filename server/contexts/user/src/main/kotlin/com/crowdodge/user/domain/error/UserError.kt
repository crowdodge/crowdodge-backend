package com.crowdodge.user.domain.error

import com.crowdodge.shared.kernel.DomainError

/**
 * user BC の想定内ドメイン失敗（§10）。
 * presentation 層で Problem(RFC9457) に変換する。
 */
sealed interface UserError : DomainError {
    sealed interface ValidationError : UserError {
        val name: String

        /** メールアドレスが空。 */
        data object BlankEmail : ValidationError {
            override val name: String = "email"
            override val code: String = "blank-email"
        }

        /** メールアドレスが基本形式（`@` を1つ含み local/domain が非空）でない。 */
        data object InvalidEmail : ValidationError {
            override val name: String = "email"
            override val code: String = "invalid-email"
        }

        /** Google ID が空。 */
        data object BlankGoogleId : ValidationError {
            override val name: String = "google_id"
            override val code: String = "blank-google-id"
        }

        /** Google カレンダー ID が空。 */
        data object BlankGoogleCalendarId : ValidationError {
            override val name: String = "google_calendar_id"
            override val code: String = "blank-google-calendar-id"
        }

        /** リマインドタイミングが不正（負値など）。 */
        data object InvalidRemindTiming : ValidationError {
            override val name: String = "remind_timing"
            override val code: String = "invalid-remind-timing"
        }

        /** 位置情報が正しい形式でない */
        data object InvalidHomeLocation : ValidationError {
            override val name: String = "home_location"
            override val code: String = "invalid-home-location"
        }

        /** FCM トークンが空。 */
        data object BlankFcmToken : ValidationError {
            override val name: String = "fcm_token"
            override val code: String = "blank-fcm-token"
        }
    }
    sealed interface ConflictError : UserError {
        data object DuplicateEmail : ConflictError {
            override val code: String = "duplicate-email"
        }

        /** 同一ユーザーが同一カレンダーを重複選択した（UNIQUE(user_uuid, google_calendar_id)）。 */
        data object DuplicateCalendar : ConflictError {
            override val code: String = "duplicate-calendar"
        }
    }
}
