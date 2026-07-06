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
            override val code: String = "BLANK_EMAIL"
        }

        /** メールアドレスが基本形式（`@` を1つ含み local/domain が非空）でない。 */
        data object InvalidEmail : ValidationError {
            override val name: String = "email"
            override val code: String = "INVALID_EMAIL"
        }

        /** Google ID が空。 */
        data object BlankGoogleId : ValidationError {
            override val name: String = "google_id"
            override val code: String = "BLANK_GOOGLE_ID"
        }

        /** Google カレンダー ID が空。 */
        data object BlankGoogleCalendarId : ValidationError {
            override val name: String = "google_calendar_id"
            override val code: String = "BLANK_GOOGLE_CALENDAR_ID"
        }

        /** リマインドタイミングが不正（負値など）。 */
        data object InvalidRemindTiming : ValidationError {
            override val name: String = "remind_timing"
            override val code: String = "INVALID_REMIND_TIMING"
        }

        /** 位置情報が正しい形式でない */
        data object InvalidHomeLocation : ValidationError {
            override val name: String = "home_location"
            override val code: String = "INVALID_HOME_LOCATION"
        }

        /** FCM トークンが空。 */
        data object BlankFcmToken : ValidationError {
            override val name: String = "fcm_token"
            override val code: String = "BLANK_FCM_TOKEN"
        }

        /** Google Subject が空。 */
        data object BlankGoogleSubject : ValidationError {
            override val name: String = "google_subject"
            override val code: String = "BLANK_GOOGLE_SUBJECT"
        }

        /** Google access token が空。 */
        data object BlankGoogleAccessToken : ValidationError {
            override val name: String = "access_token"
            override val code: String = "BLANK_GOOGLE_ACCESS_TOKEN"
        }

        /** Google refresh token が空。 */
        data object BlankGoogleRefreshToken : ValidationError {
            override val name: String = "refresh_token"
            override val code: String = "BLANK_GOOGLE_REFRESH_TOKEN"
        }

        /** Google OAuth scope 群が空。 */
        data object BlankGrantedGoogleScopes : ValidationError {
            override val name: String = "granted_scopes"
            override val code: String = "BLANK_GRANTED_GOOGLE_SCOPES"
        }

        /** 認証 refresh token hash が空。 */
        data object BlankAuthRefreshTokenHash : ValidationError {
            override val name: String = "token_hash"
            override val code: String = "BLANK_AUTH_REFRESH_TOKEN_HASH"
        }

        /** 認証 refresh token hash が 64 文字でない。 */
        data object InvalidAuthRefreshTokenHash : ValidationError {
            override val name: String = "token_hash"
            override val code: String = "INVALID_AUTH_REFRESH_TOKEN_HASH"
        }

        data object TooManyCalendarSelections : ValidationError {
            override val name: String = "calendar_ids"
            override val code: String = "TOO_MANY_CALENDAR_SELECTIONS"
        }

        data object DuplicateCalendarSelectionInput : ValidationError {
            override val name: String = "calendar_ids"
            override val code: String = "DUPLICATE_CALENDAR_SELECTION_INPUT"
        }
    }
    sealed interface ConflictError : UserError {
        data object DuplicateEmail : ConflictError {
            override val code: String = "DUPLICATE_EMAIL"
        }

        /** 同一ユーザーが同一カレンダーを重複選択した（UNIQUE(user_uuid, google_calendar_id)）。 */
        data object DuplicateCalendar : ConflictError {
            override val code: String = "DUPLICATE_CALENDAR"
        }
    }

    sealed interface AuthenticationError : UserError {
        data object InvalidGoogleToken : AuthenticationError {
            override val code: String = "INVALID_GOOGLE_TOKEN"
        }

        data object MissingGoogleScope : AuthenticationError {
            override val code: String = "MISSING_GOOGLE_SCOPE"
        }

        data object InvalidRefreshToken : AuthenticationError {
            override val code: String = "INVALID_REFRESH_TOKEN"
        }
    }

    sealed interface AuthorizationError : UserError {
        data object InsufficientCalendarAccess : AuthorizationError {
            override val code: String = "INSUFFICIENT_CALENDAR_ACCESS"
        }
    }

    sealed interface ExternalError : UserError {
        data object GoogleOAuthError : ExternalError {
            override val code: String = "GOOGLE_OAUTH_ERROR"
        }

        data object GoogleCalendarTimeoutError : ExternalError {
            override val code: String = "GOOGLE_CALENDAR_TIMEOUT"
        }
    }
}
