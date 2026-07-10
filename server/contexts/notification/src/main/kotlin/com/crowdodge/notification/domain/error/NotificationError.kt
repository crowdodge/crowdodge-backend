package com.crowdodge.notification.domain.error

import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.shared.kernel.DomainError

sealed interface NotificationError : DomainError {
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
