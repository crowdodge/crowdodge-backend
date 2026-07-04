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

        /** Google Subject が空。 */
        data object BlankGoogleSubject : ValidationError {
            override val name: String = "google_subject"
            override val code: String = "blank-google-subject"
        }

        /** Google access token が空。 */
        data object BlankGoogleAccessToken : ValidationError {
            override val name: String = "access_token"
            override val code: String = "blank-google-access-token"
        }

        /** Google refresh token が空。 */
        data object BlankGoogleRefreshToken : ValidationError {
            override val name: String = "refresh_token"
            override val code: String = "blank-google-refresh-token"
        }

        /** Google OAuth scope 群が空。 */
        data object BlankGrantedGoogleScopes : ValidationError {
            override val name: String = "granted_scopes"
            override val code: String = "blank-granted-google-scopes"
        }

        /** 認証 refresh token hash が空。 */
        data object BlankAuthRefreshTokenHash : ValidationError {
            override val name: String = "token_hash"
            override val code: String = "blank-auth-refresh-token-hash"
        }

        /** 認証 refresh token hash が 64 文字でない。 */
        data object InvalidAuthRefreshTokenHash : ValidationError {
            override val name: String = "token_hash"
            override val code: String = "invalid-auth-refresh-token-hash"
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

    sealed interface AuthenticationError : UserError {
        data object InvalidGoogleToken : AuthenticationError {
            override val code: String = "invalid-google-token"
        }

        data object MissingGoogleScope : AuthenticationError {
            override val code: String = "missing-google-scope"
        }

        data object InvalidRefreshToken : AuthenticationError {
            override val code: String = "invalid-refresh-token"
        }
    }

    sealed interface ExternalError : UserError {
        data object GoogleOAuthError : ExternalError {
            override val code: String = "google-oauth-error"
        }

        data object GoogleCalendarTimeoutError : ExternalError {
            override val code: String = "google-calendar-timeout"
        }
    }
}
