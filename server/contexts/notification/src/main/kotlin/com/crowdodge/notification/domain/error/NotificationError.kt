package com.crowdodge.notification.domain.error

import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.shared.kernel.DomainError

sealed interface NotificationError : DomainError {
    sealed interface CongestionInfoError : NotificationError {
        data object TemporarilyUnavailable : CongestionInfoError {
            override val code: String = "CONGESTION_INFO_TEMPORARILY_UNAVAILABLE"
        }

        data object PermanentlyUnavailable : CongestionInfoError {
            override val code: String = "CONGESTION_INFO_PERMANENTLY_UNAVAILABLE"
        }
    }

    sealed interface TransitionError : NotificationError {
        data class InvalidStatusTransition(
            val from: NotificationStatus,
            val to: NotificationStatus,
        ) : TransitionError {
            override val code: String = "INVALID_NOTIFICATION_STATUS_TRANSITION"
        }
    }

    sealed interface DispatchError : NotificationError {
        data object PushSendFailed : DispatchError {
            override val code: String = "PUSH_SEND_FAILED"
        }
    }
}
