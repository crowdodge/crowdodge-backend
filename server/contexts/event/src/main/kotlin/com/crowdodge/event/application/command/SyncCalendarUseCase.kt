package com.crowdodge.event.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.event.application.port.CalendarSyncGateway
import com.crowdodge.event.application.port.CalendarSyncProgressPort
import com.crowdodge.event.application.port.CalendarSyncResult
import com.crowdodge.event.application.port.CalendarWatchPort
import com.crowdodge.event.application.port.IncomingCalendarEvent
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.event.EventCancelled
import com.crowdodge.event.domain.event.EventRemindTimingChanged
import com.crowdodge.event.domain.event.EventRescheduled
import com.crowdodge.event.domain.event.EventScheduled
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 1 つの表示カレンダーを Google Calendar 同期で最新化するユースケース。
 *
 * 手順:
 *  1. webhook の channelId から同期対象カレンダーを解決する（readOnly tx）
 *  2. 継続トークンを読む（readOnly tx）
 *  3. Google から取得（外部 API は tx 外）。410 失効時はゲートウェイ内でフル再同期され
 *     [CalendarSyncResult.isFullSync] = true で返る。
 *  4. tx 内で 既存を一括ロード → メモリ diff → 一括 upsert/delete → ドメインイベントを publish
 *  5. 取り込み成功後に syncToken を前進（別 tx）。
 *
 * ローリング窓: 投影は「現在〜+Nヶ月」の窓内のみ保持する（[MaterializationWindow]）。増分同期は syncToken と
 * timeMin/timeMax を併用できない（Google 仕様）ため、窓フィルタはここで行う。窓外は保存せず、既存が窓外へ
 * 移動したら退避（削除）する。
 *
 * 差分検知でイベント種別を決める:
 *  - 既存に無い → [EventScheduled]
 *  - 予測関連（時刻/タイトル/概要/場所）が変化 → [EventRescheduled]
 *  - それ以外で remindTiming だけ変化 → [EventRemindTimingChanged]
 *  - 変化なし → 何も書かず何も発行しない
 *  - キャンセル / 窓外退避 / フル同期で結果に無い既存 → [EventCancelled]
 *
 * 不変条件: events 反映成功後にだけ syncToken を前進させる（取りこぼし防止＝at-least-once）。前進が落ちても
 * 次回同じ取得を再実行し、無変化は無発行・upsert は冪等なので二重反映しない。publish は events 反映と同一 tx
 * 内で行う（[DomainEventPublisher] の契約＝同 tx 内 outbox 永続化のみ。外部送信は publish 内で行わない）。
 */
class SyncCalendarUseCase(
    private val gateway: CalendarSyncGateway,
    private val watch: CalendarWatchPort,
    private val events: EventRepository,
    private val progress: CalendarSyncProgressPort,
    private val transactionRunner: TransactionRunner,
    private val publisher: DomainEventPublisher,
) {
    suspend fun handle(channelId: String): Either<EventError, Unit> = either {
        val userCalendarUuid = transactionRunner.readOnly {
            watch.findByChannelId(channelId)?.userCalendarUuid
        } ?: return@either Unit

        sync(userCalendarUuid).bind()
    }

    private suspend fun sync(userCalendarUuid: UserCalendarUuid): Either<EventError, Unit> = either {
        val progressState = transactionRunner.readOnly {
            CalendarSyncProgress(
                syncToken = progress.loadSyncToken(userCalendarUuid),
                materializedUntil = progress.materializedUntil(userCalendarUuid),
            )
        }
        val windowEnd = progressState.materializedUntil ?: return@either Unit
        val result = gateway.fetchUpdatedEvents(userCalendarUuid, progressState.syncToken).bind()

        transactionRunner.inTransaction { applyResult(userCalendarUuid, result, windowEnd) }
        // 取り込み確定後にだけ syncToken を前進（取りこぼし防止）。events 反映とは別 tx でよい。
        transactionRunner.inTransaction { progress.saveSyncToken(userCalendarUuid, result.nextSyncToken) }
    }

    private suspend fun applyResult(
        userCalendarUuid: UserCalendarUuid,
        result: CalendarSyncResult,
        windowEnd: Instant,
    ) {
        val now = Clock.System.now()

        val (inWindow, outOfWindow) = result.upserts
            .partition { it.isInMaterializationWindow(windowStart = now, windowEnd = windowEnd) }

        val existing = loadExisting(userCalendarUuid, result).associateBy { it.googleEventId }

        val (toUpsert, upsertEvents) = classifyInWindow(
            userCalendarUuid,
            inWindow,
            existing,
            result.cancellations.toSet(),
            now,
        )

        val toDelete = collectDeletions(result, outOfWindow, existing)
        val cancelEvents = toDelete.mapNotNull { gid -> existing[gid]?.let { EventCancelled(it.eventUuid, now) } }

        events.deleteByGoogleEventIds(userCalendarUuid, toDelete.toList())
        events.upsertAll(toUpsert)

        (upsertEvents + cancelEvents).forEach { publisher.publish(it) }
    }

    private suspend fun loadExisting(userCalendarUuid: UserCalendarUuid, result: CalendarSyncResult): List<Event> =
        if (result.isFullSync) {
            events.findAllByUserCalendarUuid(userCalendarUuid)
        } else {
            val touched = (result.upserts.map { it.googleEventId } + result.cancellations).distinct()
            events.findByGoogleEventIds(userCalendarUuid, touched)
        }

    /** 窓内の新規/更新を分類し、(永続化対象, 発行イベント) を返す。 */
    private fun classifyInWindow(
        userCalendarUuid: UserCalendarUuid,
        inWindow: List<IncomingCalendarEvent>,
        existing: Map<GoogleEventId, Event>,
        cancelledIds: Set<GoogleEventId>,
        now: Instant,
    ): Pair<List<Event>, List<DomainEvent>> {
        val toUpsert = mutableListOf<Event>()
        val emitted = mutableListOf<DomainEvent>()
        inWindow.forEach { incoming ->
            // キャンセルされたイベントは何もしない
            if (incoming.googleEventId in cancelledIds) return@forEach

            val prior = existing[incoming.googleEventId]

            // 既存に存在しないイベントは新規発行
            if (prior == null) {
                val created = Event.schedule(
                    userCalendarUuid = userCalendarUuid,
                    googleEventId = incoming.googleEventId,
                    recurringEventId = incoming.recurringEventId,
                    originalStart = incoming.originalStart,
                    eventContent = incoming.eventContent,
                )
                toUpsert += created
                emitted += EventScheduled(created.eventUuid, now)
            } else {
                val predictionChanged =
                    prior.eventContent.copy(remindTiming = null) != incoming.eventContent.copy(remindTiming = null)
                val remindChanged =
                    prior.eventContent.remindTiming != incoming.eventContent.remindTiming
                val scheduleChanged =
                    prior.eventContent.schedule != incoming.eventContent.schedule
                val contentChanged = prior.eventContent != incoming.eventContent

                if (contentChanged) {
                    toUpsert += prior.reproject(incoming.eventContent)
                }
                if (predictionChanged) {
                    emitted += EventRescheduled(prior.eventUuid, now)
                }
                if (remindChanged || scheduleChanged) {
                    emitted += EventRemindTimingChanged(prior.eventUuid, now)
                }
            }
        }
        return toUpsert to emitted
    }

    /** 削除対象 google_event_id を集める（窓外退避 + 明示キャンセル + フル同期で結果に無い既存）。 */
    private fun collectDeletions(
        result: CalendarSyncResult,
        outOfWindow: List<IncomingCalendarEvent>,
        existing: Map<GoogleEventId, Event>,
    ): Set<GoogleEventId> {
        val toDelete = LinkedHashSet<GoogleEventId>()
        outOfWindow.forEach { if (existing.containsKey(it.googleEventId)) toDelete += it.googleEventId }
        result.cancellations.forEach { if (existing.containsKey(it)) toDelete += it }
        if (result.isFullSync) {
            val present = result.upserts.map { it.googleEventId }.toSet()
            existing.keys.forEach { if (it !in present) toDelete += it }
        }
        return toDelete
    }

    private fun IncomingCalendarEvent.isInMaterializationWindow(
        windowStart: Instant,
        windowEnd: Instant,
    ): Boolean =
        eventContent.schedule.start() < windowEnd && eventContent.schedule.end() > windowStart

    private data class CalendarSyncProgress(
        val syncToken: String?,
        val materializedUntil: Instant?,
    )
}
