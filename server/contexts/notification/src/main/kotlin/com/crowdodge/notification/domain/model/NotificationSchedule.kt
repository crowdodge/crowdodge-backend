package com.crowdodge.notification.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.shared.kernel.EntityUuid
import com.crowdodge.shared.kernel.UserUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 通知スケジュール識別子（notification_schedules.notification_schedules_uuid）。 */
@JvmInline
value class NotificationScheduleUuid(override val value: Uuid) : EntityUuid {
    companion object {
        /** 新しい通知スケジュール UUID を採番する。 */
        fun new(): NotificationScheduleUuid = NotificationScheduleUuid(Uuid.random())
    }
}

/** 予定への値参照（events.event_uuid）。event BC の EventUuid とは別型（BC 間直接依存禁止）。 */
@JvmInline
value class EventUuid(override val value: Uuid) : EntityUuid

/** 通知種別（notification_schedules.kind）。 */
enum class NotificationKind(val value: String) {
    Reminder("Reminder"),
    CongestionAlert("CongestionAlert"),
    ;

    companion object {
        /** 永続化値に対応する通知種別を返す。 */
        fun fromOrNull(value: String): NotificationKind? = entries.firstOrNull { it.value == value }
    }
}

/** 通知状態（notification_schedules.status）。 */
enum class NotificationStatus(val value: String) {
    Pending("pending"),
    Processing("processing"),
    Completed("completed"),
    Failed("failed"),
    Canceled("canceled"),
    ;

    companion object {
        /** 永続化値に対応する通知状態を返す。 */
        fun fromOrNull(value: String): NotificationStatus? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 通知スケジュール（notification_schedules）。独立集約。
 * user / event は値参照（[userUuid] / [eventUuid]）。
 * 状態遷移: pending → processing → completed / failed。処理全体の失敗時だけ processing → pending を許可する。
 * cancel は pending / processing から可。
 */
@ConsistentCopyVisibility
data class NotificationSchedule private constructor(
    val notificationScheduleUuid: NotificationScheduleUuid,
    val userUuid: UserUuid,
    val eventUuid: EventUuid,
    val kind: NotificationKind,
    val notificateTime: Instant,
    val status: NotificationStatus,
) {
    /** 未処理の通知を処理中へ遷移させる。 */
    fun markProcessing(): Either<NotificationError.TransitionError, NotificationSchedule> =
        transitionTo(NotificationStatus.Processing, allowedFrom = setOf(NotificationStatus.Pending))

    /** 処理全体の失敗後に、次回実行で再取得できる状態へ戻す。 */
    fun returnToPending(): Either<NotificationError.TransitionError, NotificationSchedule> =
        transitionTo(NotificationStatus.Pending, allowedFrom = setOf(NotificationStatus.Processing))

    /** 処理中の通知を完了へ遷移させる。 */
    fun complete(): Either<NotificationError.TransitionError, NotificationSchedule> =
        transitionTo(NotificationStatus.Completed, allowedFrom = setOf(NotificationStatus.Processing))

    /** 処理中の通知を失敗へ遷移させる。 */
    fun fail(): Either<NotificationError.TransitionError, NotificationSchedule> =
        transitionTo(NotificationStatus.Failed, allowedFrom = setOf(NotificationStatus.Processing))

    /** 未処理または処理中の通知を取消へ遷移させる。 */
    fun cancel(): Either<NotificationError.TransitionError, NotificationSchedule> =
        transitionTo(
            NotificationStatus.Canceled,
            allowedFrom = setOf(NotificationStatus.Pending, NotificationStatus.Processing),
        )

    private fun transitionTo(
        to: NotificationStatus,
        allowedFrom: Set<NotificationStatus>,
    ): Either<NotificationError.TransitionError, NotificationSchedule> =
        if (status in allowedFrom) {
            copy(status = to).right()
        } else {
            NotificationError.TransitionError.InvalidStatusTransition(from = status, to = to).left()
        }

    companion object {
        /** 新規登録（新しい [NotificationScheduleUuid] を採番、状態は pending）。 */
        fun schedule(
            userUuid: UserUuid,
            eventUuid: EventUuid,
            kind: NotificationKind,
            notificateTime: Instant,
        ): NotificationSchedule = NotificationSchedule(
            notificationScheduleUuid = NotificationScheduleUuid.new(),
            userUuid = userUuid,
            eventUuid = eventUuid,
            kind = kind,
            notificateTime = notificateTime,
            status = NotificationStatus.Pending,
        )

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        @Suppress("LongParameterList")
        fun reconstitute(
            notificationScheduleUuid: NotificationScheduleUuid,
            userUuid: UserUuid,
            eventUuid: EventUuid,
            kind: NotificationKind,
            notificateTime: Instant,
            status: NotificationStatus,
        ): NotificationSchedule = NotificationSchedule(
            notificationScheduleUuid = notificationScheduleUuid,
            userUuid = userUuid,
            eventUuid = eventUuid,
            kind = kind,
            notificateTime = notificateTime,
            status = status,
        )
    }
}
