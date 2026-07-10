package com.crowdodge.notification.application.dispatch

import com.crowdodge.notification.application.port.CongestionInfo
import com.crowdodge.notification.application.port.CongestionInfoPort
import com.crowdodge.notification.application.port.DispatchReadModelPort
import com.crowdodge.notification.application.port.EventDispatchSource
import com.crowdodge.notification.application.port.OutboundPushMessage
import com.crowdodge.notification.application.port.PushNotificationSender
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner
import kotlin.time.Clock

data class DispatchResult(
    val completed: Int,
    val failed: Int,
    val canceled: Int,
)

/** 送信前の分類結果。送信対象はメッセージ列を持ち、結果を見て status を確定する。 */
private sealed interface DispatchPlan {
    val schedule: NotificationSchedule

    data class Immediate(override val schedule: NotificationSchedule, val status: NotificationStatus) : DispatchPlan

    data class Send(override val schedule: NotificationSchedule, val messages: List<OutboundPushMessage>) :
        DispatchPlan
}

/**
 * 期限到来（notificate_time <= now）の pending を processing に確保し、FCM 送信して結果を記録する。
 * 予定情報・FCM トークンは readmodel 経由のバルク取得（claim 後に各 1 クエリ）で読む。
 * 送信は全対象メッセージを sendEach ベースの一括送信 1 回に、結果保存は 1 トランザクションの saveAll にまとめる。
 * Cloud Run Job（同時実行 1）から呼ばれる前提のため行ロック排他は行わない。
 * FCM 送信はトランザクション外。
 */
class DispatchDueNotificationsUseCase(
    private val schedules: NotificationScheduleRepository,
    private val readModel: DispatchReadModelPort,
    private val congestions: CongestionInfoPort,
    private val sender: PushNotificationSender,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun execute(): DispatchResult {
        val claimed = transactions.inTransaction {
            schedules.findDue(clock.now())
                .mapNotNull { schedule -> schedule.markProcessing().getOrNull() }
                .also { schedules.saveAll(it) }
        }
        if (claimed.isEmpty()) return DispatchResult(completed = 0, failed = 0, canceled = 0)

        val eventUuids = claimed.map { it.eventUuid }.distinct()
        val sourceByEvent = readModel.eventSources(eventUuids)
        val congestionByEvent = congestions.findAll(eventUuids)
        val tokensByUser = readModel.fcmTokens(claimed.map { it.userUuid }.distinct())

        val plans = claimed.map { schedule ->
            plan(
                schedule = schedule,
                source = sourceByEvent[schedule.eventUuid],
                congestion = congestionByEvent[schedule.eventUuid],
                tokens = tokensByUser[schedule.userUuid].orEmpty(),
            )
        }

        val sendResults = sender.sendAll(plans.filterIsInstance<DispatchPlan.Send>().flatMap { it.messages })

        var resultIndex = 0
        val outcomes = plans.map { plan ->
            val nextStatus = when (plan) {
                is DispatchPlan.Immediate -> plan.status
                is DispatchPlan.Send -> {
                    val results = sendResults.subList(resultIndex, resultIndex + plan.messages.size)
                    resultIndex += plan.messages.size
                    if (results.any { it.isRight() }) NotificationStatus.Completed else NotificationStatus.Failed
                }
            }
            transition(plan.schedule, nextStatus)
        }
        transactions.inTransaction { schedules.saveAll(outcomes) }

        return DispatchResult(
            completed = outcomes.count { it.status == NotificationStatus.Completed },
            failed = outcomes.count { it.status == NotificationStatus.Failed },
            canceled = outcomes.count { it.status == NotificationStatus.Canceled },
        )
    }

    private fun plan(
        schedule: NotificationSchedule,
        source: EventDispatchSource?,
        congestion: CongestionInfo?,
        tokens: List<String>,
    ): DispatchPlan = when {
        source == null -> DispatchPlan.Immediate(schedule, NotificationStatus.Canceled)

        schedule.kind == NotificationKind.CongestionAlert && congestion == null -> {
            // 混雑情報が本体の通知のため、情報がない間は送信しない（congestion BC 実装後に自動で流れ始める）
            DispatchPlan.Immediate(schedule, NotificationStatus.Canceled)
        }

        tokens.isEmpty() -> DispatchPlan.Immediate(schedule, NotificationStatus.Failed)

        else -> {
            val notification = NotificationMessageFactory.create(source, congestion)
            DispatchPlan.Send(schedule, tokens.map { token -> OutboundPushMessage(token, notification) })
        }
    }

    private fun transition(schedule: NotificationSchedule, nextStatus: NotificationStatus): NotificationSchedule =
        when (nextStatus) {
            NotificationStatus.Completed -> schedule.complete().getOrNull() ?: schedule
            NotificationStatus.Failed -> schedule.fail().getOrNull() ?: schedule
            NotificationStatus.Canceled -> schedule.cancel().getOrNull() ?: schedule
            else -> schedule
        }
}
