package com.crowdodge.event.domain.error

import com.crowdodge.shared.kernel.DomainError

/**
 * event BC の想定内ドメイン失敗（§10）。
 * presentation 層で Problem(RFC9457) に変換する。
 */
sealed interface EventError : DomainError {
    sealed interface ValidationError : EventError {
        val name: String

        /** Google イベント（インスタンス）ID が空。 */
        data object BlankGoogleEventId : ValidationError {
            override val name: String = "google_event_id"
            override val code: String = "BLANK_GOOGLE_EVENT_ID"
        }

        /** Google シリーズ（マスタ）ID が空。 */
        data object BlankRecurringEventId : ValidationError {
            override val name: String = "recurring_event_id"
            override val code: String = "BLANK_RECURRING_EVENT_ID"
        }

        /** 開始/終了時刻が設定されていない。 */
        data object BlankSchedule : ValidationError {
            override val name: String = "schedule"
            override val code: String = "BLANK_SCHEDULE"
        }

        /** 予定の形式が不正（時刻指定 XOR 終日に反する＝片方だけ・時刻と日付の併存）。 */
        data object InvalidScheduleFormat : ValidationError {
            override val name: String = "schedule"
            override val code: String = "INVALID_SCHEDULE_FORMAT"
        }

        /** 開始/終了が範囲をなさない（時刻指定は `start < end`、終日は `startDate < endDate` でない）。 */
        data object InvalidScheduleRange : ValidationError {
            override val name: String = "schedule"
            override val code: String = "INVALID_SCHEDULE_RANGE"
        }

        /** リマインドタイミングが不正（負値など）。 */
        data object InvalidRemindTiming : ValidationError {
            override val name: String = "remind_timing"
            override val code: String = "INVALID_REMIND_TIMING"
        }
    }

    sealed interface ExternalError : EventError {
        data object GoogleCalendarError : ExternalError {
            override val code: String = "GOOGLE_CALENDAR_ERROR"
        }

        data object GoogleCalendarTimeoutError : ExternalError {
            override val code: String = "GOOGLE_CALENDAR_TIMEOUT"
        }
    }
}
