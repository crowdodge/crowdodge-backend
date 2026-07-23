package com.crowdodge.notification.domain.error

import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.shared.kernel.DomainError

/** 通知 BC で扱うエラー。 */
sealed interface NotificationError : DomainError {
    /** 混雑情報を取得できなかったエラー。 */
    sealed interface CongestionInfoError : NotificationError {
        /** 後続の実行で回復する可能性がある取得失敗。 */
        data object TemporarilyUnavailable : CongestionInfoError {
            override val code: String = "CONGESTION_INFO_TEMPORARILY_UNAVAILABLE"
        }

        /** 後続の実行でも回復を期待できない取得失敗。 */
        data object PermanentlyUnavailable : CongestionInfoError {
            override val code: String = "CONGESTION_INFO_PERMANENTLY_UNAVAILABLE"
        }
    }

    /** 通知状態を遷移できなかったエラー。 */
    sealed interface TransitionError : NotificationError {
        /** 現在の状態から要求された状態へ遷移できないことを表す。 */
        data class InvalidStatusTransition(
            val from: NotificationStatus,
            val to: NotificationStatus,
        ) : TransitionError {
            override val code: String = "INVALID_NOTIFICATION_STATUS_TRANSITION"
        }
    }

    /** Push通知の送信処理で発生したエラー。 */
    sealed interface DispatchError : NotificationError {
        /** Push通知を送信できなかったことを表す。 */
        data object PushSendFailed : DispatchError {
            override val code: String = "PUSH_SEND_FAILED"
        }
    }
}
