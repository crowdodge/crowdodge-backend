@file:Suppress("TooGenericExceptionCaught")

package com.crowdodge.notification.application.dispatch

import com.crowdodge.notification.application.port.CongestionInfo
import com.crowdodge.notification.application.port.CongestionInfoPort
import com.crowdodge.notification.application.port.CongestionInfoResult
import com.crowdodge.notification.application.port.DispatchReadModelPort
import com.crowdodge.notification.application.port.EventDispatchSource
import com.crowdodge.notification.application.port.OutboundPushMessage
import com.crowdodge.notification.application.port.PushNotificationSender
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.model.NotificationScheduleUuid
import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/** 通知送信後の状態別件数。 */
data class DispatchResult(
    val completed: Int,
    val failed: Int,
    val canceled: Int,
    val retried: Int = 0,
)

/** 送信前に決定した通知の処理方針。 */
private sealed interface DispatchPlan {
    val schedule: NotificationSchedule

    data class Immediate(override val schedule: NotificationSchedule, val status: NotificationStatus) : DispatchPlan

    data class Send(override val schedule: NotificationSchedule, val messages: List<OutboundPushMessage>) :
        DispatchPlan

    data class Retry(override val schedule: NotificationSchedule) : DispatchPlan
}

/** 送信方針の決定に必要な一括取得結果。 */
private data class DispatchInputs(
    val dispatchableScheduleUuids: Set<NotificationScheduleUuid>,
    val sourceByEvent: Map<EventUuid, EventDispatchSource>,
    val congestionByEvent: Map<EventUuid, CongestionInfoResult>,
    val tokensByUser: Map<UserUuid, List<String>>,
)

/** 同じ予定で期限を迎えた通知から、送信対象となる最後の一件を選ぶ。 */
private fun selectDispatchableScheduleUuids(
    claimed: List<NotificationSchedule>,
    sourceByEvent: Map<EventUuid, EventDispatchSource>,
    now: Instant,
): Set<NotificationScheduleUuid> = claimed
    // 通知種別やユーザーで分けると古い通知も残るため、同じ予定では種別を問わず直前の一件だけを選ぶ。
    .groupBy { schedule -> schedule.eventUuid }
    .mapNotNull { (eventUuid, eventSchedules) ->
        val source = sourceByEvent[eventUuid] ?: return@mapNotNull null
        if (now >= source.start) return@mapNotNull null
        eventSchedules.maxWithOrNull(
            compareBy<NotificationSchedule> { schedule -> schedule.notificateTime }
                .thenBy { schedule -> schedule.notificationScheduleUuid.value.toString() },
        )?.notificationScheduleUuid
    }.toSet()

/** 一時的に送信できなかった通知を再試行可能な状態へ戻す。 */
private fun NotificationSchedule.returnToPendingOrThrow(): NotificationSchedule =
    returnToPending().fold(
        ifLeft = { error -> throw IllegalStateException(error.toString()) },
        ifRight = { it },
    )

/** 通知を指定した終端状態へ遷移させる。 */
private fun NotificationSchedule.transitionTo(nextStatus: NotificationStatus): NotificationSchedule =
    when (nextStatus) {
        NotificationStatus.Completed -> complete().getOrNull() ?: this
        NotificationStatus.Failed -> fail().getOrNull() ?: this
        NotificationStatus.Canceled -> cancel().getOrNull() ?: this
        else -> this
    }

/** 期限を迎えた通知を確保し、必要な情報を解決して送信する。 */
class DispatchDueNotificationsUseCase(
    private val schedules: NotificationScheduleRepository,
    private val readModel: DispatchReadModelPort,
    private val congestions: CongestionInfoPort,
    private val sender: PushNotificationSender,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** 今回処理できる通知を送信し、状態別の件数を返す。 */
    suspend fun execute(): DispatchResult {
        val now = clock.now()
        val claimedSchedules = claimDueSchedules(now)
        if (claimedSchedules.isEmpty()) return DispatchResult(completed = 0, failed = 0, canceled = 0)

        return try {
            dispatchClaimedSchedules(claimedSchedules, now)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { returnClaimedToPending(claimedSchedules, cancellation) }
            throw cancellation
        } catch (failure: Exception) {
            returnClaimedToPending(claimedSchedules, failure)
            throw failure
        }
    }

    /** 期限を迎えた通知を processing 状態で確保する。 */
    private suspend fun claimDueSchedules(now: Instant): List<NotificationSchedule> =
        transactions.inTransaction {
            // Cloud Run Job を同時実行数 1 で運用するため、行ロックによる多重実行対策は行わない。
            schedules.findDue(now)
                .mapNotNull { schedule -> schedule.markProcessing().getOrNull() }
                .also { schedules.saveAll(it) }
        }

    /** 確保した通知の送信と状態保存を行う。 */
    private suspend fun dispatchClaimedSchedules(
        claimedSchedules: List<NotificationSchedule>,
        now: Instant,
    ): DispatchResult {
        val inputs = loadDispatchInputs(claimedSchedules, now)
        val plans = planDispatches(claimedSchedules, inputs)

        val messages = plans.filterIsInstance<DispatchPlan.Send>().flatMap { it.messages }
        // 外部送信を DB トランザクションに含めると通信待ちの間も接続とロックを保持するため、重複を許容する at-least-once とする。
        val sendResults = if (messages.isEmpty()) emptyList() else sender.sendAll(messages)

        var resultIndex = 0
        val outcomes = plans.map { dispatchPlan ->
            val nextStatus = when (dispatchPlan) {
                is DispatchPlan.Immediate -> dispatchPlan.status
                is DispatchPlan.Retry -> NotificationStatus.Pending
                is DispatchPlan.Send -> {
                    val results = sendResults.subList(resultIndex, resultIndex + dispatchPlan.messages.size)
                    resultIndex += dispatchPlan.messages.size
                    if (results.any { it.isRight() }) NotificationStatus.Completed else NotificationStatus.Failed
                }
            }
            if (dispatchPlan is DispatchPlan.Retry) {
                dispatchPlan.schedule.returnToPendingOrThrow()
            } else {
                dispatchPlan.schedule.transitionTo(nextStatus)
            }
        }
        transactions.inTransaction { schedules.saveAll(outcomes) }

        return DispatchResult(
            completed = outcomes.count { it.status == NotificationStatus.Completed },
            failed = outcomes.count { it.status == NotificationStatus.Failed },
            canceled = outcomes.count { it.status == NotificationStatus.Canceled },
            retried = outcomes.count { it.status == NotificationStatus.Pending },
        )
    }

    /** 送信方針の決定に必要な値を一括取得する。 */
    private suspend fun loadDispatchInputs(
        claimedSchedules: List<NotificationSchedule>,
        now: Instant,
    ): DispatchInputs {
        val eventUuids = claimedSchedules.map { it.eventUuid }.distinct()
        val sourceByEvent = readModel.eventSources(eventUuids)
        val dispatchableScheduleUuids = selectDispatchableScheduleUuids(claimedSchedules, sourceByEvent, now)
        val dispatchableSchedules = claimedSchedules.filter {
            it.notificationScheduleUuid in dispatchableScheduleUuids
        }
        val dispatchableEventUuids = dispatchableSchedules.map { it.eventUuid }.distinct()
        val congestionByEvent = if (dispatchableEventUuids.isEmpty()) {
            emptyMap()
        } else {
            congestions.findAll(dispatchableEventUuids).also {
                requireCompleteCongestionResults(dispatchableEventUuids, it)
            }
        }
        val dispatchableUserUuids = dispatchableSchedules.map { it.userUuid }.distinct()
        val tokensByUser = if (dispatchableUserUuids.isEmpty()) {
            emptyMap()
        } else {
            readModel.fcmTokens(dispatchableUserUuids)
        }
        return DispatchInputs(dispatchableScheduleUuids, sourceByEvent, congestionByEvent, tokensByUser)
    }

    /** 確保した通知ごとの処理方針を決定する。 */
    private fun planDispatches(
        claimedSchedules: List<NotificationSchedule>,
        inputs: DispatchInputs,
    ): List<DispatchPlan> = claimedSchedules.map { schedule ->
        if (schedule.notificationScheduleUuid !in inputs.dispatchableScheduleUuids) {
            DispatchPlan.Immediate(schedule, NotificationStatus.Canceled)
        } else {
            planDispatch(
                schedule = schedule,
                source = inputs.sourceByEvent.getValue(schedule.eventUuid),
                congestion = inputs.congestionByEvent.getValue(schedule.eventUuid),
                tokens = inputs.tokensByUser[schedule.userUuid].orEmpty(),
            )
        }
    }

    /** 処理中に失敗した通知を次回実行可能な状態へ戻す。 */
    private suspend fun returnClaimedToPending(
        claimedSchedules: List<NotificationSchedule>,
        originalFailure: Throwable,
    ) {
        try {
            transactions.inTransaction {
                schedules.saveAll(
                    claimedSchedules.map { schedule ->
                        schedule.returnToPendingOrThrow()
                    },
                )
            }
        } catch (recoveryFailure: Throwable) {
            originalFailure.addSuppressed(recoveryFailure)
        }
    }

    /** 一件の通知について送信・再試行・即時確定を選ぶ。 */
    private fun planDispatch(
        schedule: NotificationSchedule,
        source: EventDispatchSource?,
        congestion: CongestionInfoResult,
        tokens: List<String>,
    ): DispatchPlan = when {
        source == null -> DispatchPlan.Immediate(schedule, NotificationStatus.Canceled)

        schedule.kind == NotificationKind.CongestionAlert &&
            congestion is CongestionInfoResult.Failure &&
            congestion.error == NotificationError.CongestionInfoError.TemporarilyUnavailable ->
            DispatchPlan.Retry(schedule)

        // Reminder の主目的まで混雑情報の可用性に従属させないため、取得失敗時も混雑情報なしで送る。
        else -> planAvailableCongestion(schedule, source, congestion.availableInfo(), tokens)
    }

    /** 利用可能な混雑情報と端末トークンから処理方針を決める。 */
    private fun planAvailableCongestion(
        schedule: NotificationSchedule,
        source: EventDispatchSource,
        congestion: CongestionInfo?,
        tokens: List<String>,
    ): DispatchPlan = when {
        schedule.kind == NotificationKind.CongestionAlert && congestion == null ->
            DispatchPlan.Immediate(schedule, NotificationStatus.Canceled)

        tokens.isEmpty() -> DispatchPlan.Immediate(schedule, NotificationStatus.Failed)

        else -> {
            val notification = NotificationMessageFactory.create(source, congestion)
            DispatchPlan.Send(schedule, tokens.map { token -> OutboundPushMessage(token, notification) })
        }
    }

    /** 通知本文に利用できる混雑情報を取り出す。 */
    private fun CongestionInfoResult.availableInfo(): CongestionInfo? = when (this) {
        is CongestionInfoResult.Success -> info
        is CongestionInfoResult.Failure -> null
    }

    /** 要求した予定の結果がすべて返されたことを確認する。 */
    private fun requireCompleteCongestionResults(
        eventUuids: List<EventUuid>,
        congestionByEvent: Map<EventUuid, CongestionInfoResult>,
    ) {
        check(congestionByEvent.keys.containsAll(eventUuids)) {
            "CongestionInfoPort must return a result for every requested event UUID"
        }
    }
}
